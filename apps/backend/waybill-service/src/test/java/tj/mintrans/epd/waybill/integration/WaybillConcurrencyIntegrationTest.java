package tj.mintrans.epd.waybill.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tj.mintrans.epd.waybill.client.MasterDataClient;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.service.AggregatorService;
import tj.mintrans.epd.waybill.web.error.ApiErrors;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Инвариант «один ДЕЙСТВУЮЩИЙ путевой лист на ТС и на водителя» под РЕАЛЬНОЙ параллельностью
 * (зеркалит scripts/concurrency-test.ps1) — защищён частичными уникальными индексами
 * V7__active_waybill_constraints.sql (uq_active_waybill_vehicle / uq_active_waybill_driver).
 *
 * <p>Мокнутый WaybillRepository структурно не может это проверить: TOCTOU-гонка «check-then-insert»
 * (findByVehicleRegNumberAndStatusIn пуст у ВСЕХ конкурентных транзакций под READ_COMMITTED,
 * все проходят проверку и пытаются вставить) обнаруживается только реальным СУБД-ограничением
 * на COMMIT. Используем AggregatorService.create() (как в concurrency-test.ps1) — легаси-поток
 * агрегатора создаёт документ сразу в статусе CREATED (открытый) за одну транзакцию, в отличие
 * от портального WaybillService.create(), который останавливается на DRAFT (ещё не «действует»,
 * V7 намеренно его не блокирует).</p>
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WaybillConcurrencyIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final String ORG_RMA = "025680800";
    private static final String ORG_ID = "org-" + ORG_RMA;
    private static final String VEHICLE = "0114TJ01";
    private static final String VEHICLE_ID = "veh-" + VEHICLE;
    private static final String DRIVER_RMA = "461930031";
    private static final int CONCURRENT_REQUESTS = 8; // как в concurrency-test.ps1 (1..8)

    @Autowired
    AggregatorService aggregatorService;
    @Autowired
    WaybillRepository waybillRepository;

    @MockitoBean
    MasterDataClient masterData;
    @MockitoBean
    KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void stubMasterDataAndKafka() {
        Map<String, Object> org = new HashMap<>();
        org.put("id", ORG_ID);
        org.put("rma", ORG_RMA);
        org.put("typeCompany", 1);
        org.put("blocked", false);
        org.put("licenseTo", LocalDate.now().plusYears(2).toString());
        org.put("regionId", 1);

        Map<String, Object> driver = new HashMap<>();
        driver.put("rma", DRIVER_RMA);
        driver.put("organizationId", ORG_ID);
        driver.put("suspended", false);
        driver.put("licenseValidTo", LocalDate.now().plusYears(2).toString());
        driver.put("medCertValidTo", LocalDate.now().plusMonths(6).toString());
        // AggregatorService всегда создаёт WB_TAXI (transportType=4, требуется категория ВУ "B")
        // — runBlockingChecks (assertLicenseMatchesVehicle) сверяет категорию с типом ТС.
        driver.put("licenseCategories", "B");

        Map<String, Object> vehicle = new HashMap<>();
        vehicle.put("id", VEHICLE_ID);
        vehicle.put("registrationNumber", VEHICLE);
        vehicle.put("organizationId", ORG_ID);
        vehicle.put("blocked", false);
        vehicle.put("transportType", 4); // легковой/такси (WB_TAXI — тип аггрегаторского ПЛ)
        vehicle.put("techInspectionValidTo", LocalDate.now().plusMonths(6).toString());
        vehicle.put("controlCardValidTo", LocalDate.now().plusMonths(6).toString());
        vehicle.put("insuranceValidTo", LocalDate.now().plusMonths(6).toString());
        vehicle.put("odometer", 0);

        when(masterData.findOrganization(ORG_RMA)).thenReturn(Optional.of(org));
        when(masterData.findDriver(DRIVER_RMA)).thenReturn(Optional.of(driver));
        when(masterData.findVehicle(VEHICLE)).thenReturn(Optional.of(vehicle));
        when(masterData.effectivePolicies(anyString(), anyString())).thenReturn(Map.of());
        when(masterData.listFieldDefinitions(anyString())).thenReturn(List.of());
        when(masterData.notificationSettings()).thenReturn(Map.of());
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void concurrentAggregatorCreate_onSameVehicleAndDriver_admitsExactlyOne() throws Exception {
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        var exitDate = OffsetDateTime.now();
        AtomicInteger unexpected = new AtomicInteger();

        List<Future<Boolean>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    aggregatorService.create(ORG_RMA, VEHICLE, DRIVER_RMA, null, exitDate, null, 50);
                    return true; // успешно создан и переведён в CREATED
                } catch (DataIntegrityViolationException | ApiErrors.ConflictException expected) {
                    // Ожидаемый отказ: либо СУБД отклонила вставку по частичному уникальному
                    // индексу (гонка на COMMIT), либо runBlockingChecks увидел уже
                    // зафиксированный конкурентом документ (не гонка — застал после коммита).
                    return false;
                }
            }));
        }
        start.countDown();

        int succeeded = 0;
        int rejected = 0;
        for (Future<Boolean> f : futures) {
            try {
                if (f.get(30, TimeUnit.SECONDS)) succeeded++; else rejected++;
            } catch (ExecutionException e) {
                unexpected.incrementAndGet();
                System.err.println("Неожиданная ошибка конкурентного create: " + e.getCause());
            }
        }
        pool.shutdown();

        System.out.printf("Конкурентных create=%d: успешно=%d, отклонено=%d, неожиданно=%d%n",
                CONCURRENT_REQUESTS, succeeded, rejected, unexpected.get());
        assertThat(unexpected.get())
                .as("все отказы должны быть ожидаемого вида (DataIntegrityViolationException/ConflictException)")
                .isZero();
        // NB: AggregatorService имеет legacy-семантику «новый запрос аннулирует предыдущий
        // действующий ПЛ» (cancelOpenWaybills), поэтому при неидеальной одновременности
        // возможна цепочка cancel+create (несколько потоков по очереди отменяют предыдущий
        // и создают новый — несколько "успехов" подряд, а не только 1). Жёстко гонка на
        // частичном уникальном индексе (V7) проявляется, только если ≥2 потоков одновременно
        // проходят проверку runBlockingChecks ДО того, как кто-то из них закоммитится — тогда
        // СУБД отклоняет вставку второго (DataIntegrityViolationException). В любом случае
        // хотя бы один конкурент обязан выиграть гонку (не может отклониться абсолютно всё —
        // первый закоммитившийся не конфликтует ни с кем, т.к. ничего ещё не зафиксировано).
        assertThat(succeeded).as("хотя бы один конкурент должен успешно создать документ").isGreaterThanOrEqualTo(1);
        assertThat(succeeded + rejected).isEqualTo(CONCURRENT_REQUESTS);

        // ГЛАВНЫЙ инвариант — проверяется НАПРЯМУЮ по БД (не по ответам приложения), как в
        // scripts/concurrency-test.ps1: после того как гонка отгремела, не более ОДНОГО
        // действующего ПЛ на это ТС и на этого водителя — частичный уникальный индекс V7
        // физически не допускает второй одновременно открытый ряд.
        var openByVehicle = waybillRepository.findByVehicleRegNumberAndStatusIn(VEHICLE, WaybillStatus.OPEN_STATUSES);
        var openByDriver = waybillRepository.findByDriverRmaAndStatusIn(DRIVER_RMA, WaybillStatus.OPEN_STATUSES);
        assertThat(openByVehicle).as("действующих ПЛ на ТС").hasSize(1);
        assertThat(openByDriver).as("действующих ПЛ на водителя").hasSize(1);
        assertThat(openByVehicle.get(0).getId()).isEqualTo(openByDriver.get(0).getId());
        assertThat(openByVehicle.get(0).getStatus()).isEqualTo(WaybillStatus.CREATED);
    }
}
