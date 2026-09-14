package tj.mintrans.epd.waybill.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tj.mintrans.epd.waybill.client.MasterDataClient;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillPayment;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.repository.WaybillPaymentRepository;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.repository.WaybillStatusEventRepository;
import tj.mintrans.epd.waybill.repository.WaybillTitleRepository;
import tj.mintrans.epd.waybill.service.WaybillNumberGenerator;
import tj.mintrans.epd.waybill.service.WaybillService;

import java.time.LocalDate;
import java.time.Year;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Полный жизненный цикл ПЛ (раздел 15 ТЗ) против РЕАЛЬНОГО Postgres с РЕАЛЬНЫМИ миграциями
 * Flyway (V1..V19) — зеркалит scripts/smoke-test.ps1 (раздел «4. Полный жизненный цикл»),
 * но идёт через сервисный слой (WaybillService) напрямую, минуя HTTP/security (это уже
 * покрыто @WebMvcTest-тестами прав доступа). Ловит класс багов, недоступный мок-тестам:
 * ошибки миграций, битые запросы, нарушения ограничений БД (constraints), реальные
 * JSON-колонки (organization_snapshot/vehicle_snapshot/type_data).
 *
 * <p>MasterDataClient (HTTP-клиент master-data-service) и KafkaTemplate (события статусов)
 * замоканы — master-data-service и брокер Kafka в этом тесте не поднимаются; только
 * персистентность/бизнес-логика waybill-service проверяется против реальной БД.</p>
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WaybillLifecycleIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Смоук-тест (scripts/smoke-test.ps1) прогоняется с PAYMENT_ENABLED=true:
        // Т3 -> AWAITING_PAYMENT, confirm-payment -> PAID -> READY. Зеркалим тот же контур,
        // иначе оплатный этап статусной машины (AWAITING_PAYMENT/PAID) остался бы непроверенным.
        registry.add("epd.payment.enabled", () -> "true");
    }

    private static final String ORG_RMA = "025680800";
    private static final String ORG_ID = "org-" + ORG_RMA;
    private static final String VEHICLE = "0114TJ01";
    private static final String VEHICLE_ID = "veh-" + VEHICLE;
    private static final String DRIVER_RMA = "461930031";
    private static final String DOCTOR_RMA = "111111111";
    private static final String MECHANIC_RMA = "222222222";
    private static final String DISPATCHER_RMA = "333333333";

    @Autowired
    WaybillService waybillService;
    @Autowired
    WaybillRepository waybillRepository;
    @Autowired
    WaybillTitleRepository titleRepository;
    @Autowired
    WaybillPaymentRepository paymentRepository;
    @Autowired
    WaybillStatusEventRepository eventRepository;

    @MockitoBean
    MasterDataClient masterData;
    @MockitoBean
    KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void stubMasterDataAndKafka() {
        var org = organization();
        var driver = driver();
        var vehicle = vehicle();

        when(masterData.findOrganization(ORG_RMA)).thenReturn(Optional.of(org));
        when(masterData.findDriver(DRIVER_RMA)).thenReturn(Optional.of(driver));
        when(masterData.findVehicle(VEHICLE)).thenReturn(Optional.of(vehicle));
        when(masterData.findEmployee(DOCTOR_RMA)).thenReturn(Optional.of(employee(DOCTOR_RMA, 1, "Раҳимова С.")));
        when(masterData.findEmployee(MECHANIC_RMA)).thenReturn(Optional.of(employee(MECHANIC_RMA, 2, "Қосимов Ф.")));
        when(masterData.findEmployee(DISPATCHER_RMA)).thenReturn(Optional.of(employee(DISPATCHER_RMA, 3, "Назарова М.")));
        // Пустая карта политик -> движок политик недоступен -> безопасные фолбэки в WaybillService
        // (require_med_pre/require_tech_check по умолчанию true, max_validity_days = лимит типа).
        when(masterData.effectivePolicies(anyString(), anyString())).thenReturn(Map.of());
        when(masterData.listFieldDefinitions(anyString())).thenReturn(List.of());
        when(masterData.notificationSettings()).thenReturn(Map.of());

        // Kafka-мост (KafkaEventBridge) не должен пытаться достучаться до реального брокера.
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    private static Map<String, Object> organization() {
        Map<String, Object> org = new HashMap<>();
        org.put("id", ORG_ID);
        org.put("rma", ORG_RMA);
        org.put("typeCompany", 1); // перевозчик общего пользования — лицензия/контрольная карточка обязательны
        org.put("blocked", false);
        org.put("licenseTo", LocalDate.now().plusYears(2).toString());
        org.put("regionId", 1);
        org.put("name", "КВД Автобуси Душанбе");
        return org;
    }

    private static Map<String, Object> driver() {
        Map<String, Object> driver = new HashMap<>();
        driver.put("rma", DRIVER_RMA);
        driver.put("organizationId", ORG_ID);
        driver.put("fullName", "Ахмедзода Зохид");
        driver.put("suspended", false);
        driver.put("licenseValidTo", LocalDate.now().plusYears(2).toString());
        driver.put("medCertValidTo", LocalDate.now().plusMonths(6).toString());
        driver.put("licenseCategories", "D"); // требуется для transportType=1 (автобус)
        return driver;
    }

    private static Map<String, Object> vehicle() {
        Map<String, Object> vehicle = new HashMap<>();
        vehicle.put("id", VEHICLE_ID);
        vehicle.put("registrationNumber", VEHICLE);
        vehicle.put("organizationId", ORG_ID);
        vehicle.put("blocked", false);
        vehicle.put("transportType", 1); // автобус — требуется для WB_BUS
        vehicle.put("techInspectionValidTo", LocalDate.now().plusMonths(6).toString());
        vehicle.put("controlCardValidTo", LocalDate.now().plusMonths(6).toString());
        vehicle.put("insuranceValidTo", LocalDate.now().plusMonths(6).toString());
        vehicle.put("odometer", 1000);
        vehicle.put("brand", "Акиа");
        return vehicle;
    }

    private static Map<String, Object> employee(String rma, int type, String name) {
        Map<String, Object> e = new HashMap<>();
        e.put("rma", rma);
        e.put("organizationId", ORG_ID);
        e.put("type", type);
        e.put("name", name);
        return e;
    }

    @Test
    void fullLifecycle_draftToCompleted_persistsRealDbStateAtEachStep() {
        // 1. create -> DRAFT
        Waybill created = waybillService.create(WaybillType.WB_BUS, ORG_RMA, VEHICLE, DRIVER_RMA,
                null, "URBAN", "Smoke-маршрут", null, null, null);
        UUID id = created.getId();
        assertThat(created.getStatus()).isEqualTo(WaybillStatus.DRAFT);
        assertThat(created.getCommunicationType()).isEqualTo("URBAN");
        assertThat(fresh(id).getStatus()).isEqualTo(WaybillStatus.DRAFT);
        // Иммутабельные снимки мастер-данных (JSON-колонки) реально записались и читаются.
        assertThat(fresh(id).getOrganizationSnapshot()).containsEntry("rma", ORG_RMA);
        assertThat(fresh(id).getVehicleSnapshot()).containsEntry("registrationNumber", VEHICLE);

        // 2. Т1 диспетчером -> CREATED
        Waybill afterT1 = waybillService.signT1(id, DISPATCHER_RMA, null, 1);
        assertThat(afterT1.getStatus()).isEqualTo(WaybillStatus.CREATED);
        assertThat(fresh(id).getStatus()).isEqualTo(WaybillStatus.CREATED);
        assertThat(titleRepository.findByWaybillIdAndTitleType(id, "T1")).isPresent();

        // 3. Т2 врачом (медосмотр) — сам по себе статус не меняет (ждём ещё и техконтроль)
        Waybill afterMed = waybillService.confirmMed(id, DOCTOR_RMA, true, Map.of("pulse", 70, "alcotest", 0));
        assertThat(afterMed.isMedPassed()).isTrue();
        assertThat(fresh(id).isMedPassed()).isTrue();
        assertThat(fresh(id).getStatus()).isEqualTo(WaybillStatus.CREATED);

        // 4. Т3 механиком (техконтроль) -> AWAITING_PAYMENT (Т2+Т3 пройдены, оплата ещё не подтверждена)
        Waybill afterTech = waybillService.confirmTech(id, MECHANIC_RMA, true, Map.of("brakes", "OK"));
        assertThat(afterTech.getStatus()).isEqualTo(WaybillStatus.AWAITING_PAYMENT);
        assertThat(fresh(id).getStatus()).isEqualTo(WaybillStatus.AWAITING_PAYMENT);
        assertThat(paymentRepository.findByWaybillId(id)).isPresent();
        assertThat(paymentRepository.findByWaybillId(id).orElseThrow().getStatus())
                .isEqualTo(WaybillPayment.STATUS_PENDING);

        // 5. Оплата бухгалтером -> PAID -> READY + национальный номер + журнальный номер филиала
        Waybill afterPayment = waybillService.confirmPayment(id, "CASH", null, "444444444");
        assertThat(afterPayment.getStatus()).isEqualTo(WaybillStatus.READY);
        assertThat(afterPayment.getNumber()).matches("^\\d{2}-\\d{2}-\\d{2}-\\d{7}-\\d$");
        assertThat(WaybillNumberGenerator.isValid(afterPayment.getNumber())).isTrue();
        assertThat(afterPayment.getBranchSerial()).isNotNull().isGreaterThanOrEqualTo(1);
        assertThat(afterPayment.getBranchSerialYear()).isEqualTo((short) Year.now().getValue());

        Waybill persistedReady = fresh(id);
        assertThat(persistedReady.getStatus()).isEqualTo(WaybillStatus.READY);
        assertThat(persistedReady.getNumber()).isEqualTo(afterPayment.getNumber());
        assertThat(persistedReady.getBranchSerial()).isEqualTo(afterPayment.getBranchSerial());
        assertThat(persistedReady.getBranchSerialYear()).isEqualTo(afterPayment.getBranchSerialYear());
        assertThat(paymentRepository.findByWaybillId(id).orElseThrow().getStatus())
                .isEqualTo(WaybillPayment.STATUS_CONFIRMED);

        // 6. Выдача -> ISSUED
        Waybill afterIssue = waybillService.issue(id, "PIN");
        assertThat(afterIssue.getStatus()).isEqualTo(WaybillStatus.ISSUED);
        assertThat(fresh(id).getStatus()).isEqualTo(WaybillStatus.ISSUED);

        // 7. Т4 (выезд на линию) -> ACTIVE; одометр выезда берётся из снимка ТС (1000)
        Waybill afterActivate = waybillService.activate(id, DISPATCHER_RMA, null);
        assertThat(afterActivate.getStatus()).isEqualTo(WaybillStatus.ACTIVE);
        assertThat(afterActivate.getOdometerExit()).isEqualTo(1000);
        int odometerExit = afterActivate.getOdometerExit();

        // 8. Т5 (возврат, +120 км) -> RETURNED
        Waybill afterReturn = waybillService.returnTrip(id, DISPATCHER_RMA, odometerExit + 120);
        assertThat(afterReturn.getStatus()).isEqualTo(WaybillStatus.RETURNED);
        assertThat(fresh(id).getOdometerEntry()).isEqualTo(odometerExit + 120);

        // 9. Послерейсовый медосмотр (Т6) — обязателен для закрытия пассажирского ПЛ (WB_BUS)
        waybillService.confirmMed(id, DOCTOR_RMA, true, null);
        assertThat(titleRepository.findByWaybillIdAndTitleType(id, "T6")).isPresent();

        // 10. Закрытие -> COMPLETED
        Waybill closed = waybillService.close(id, DISPATCHER_RMA);
        assertThat(closed.getStatus()).isEqualTo(WaybillStatus.COMPLETED);
        assertThat(fresh(id).getStatus()).isEqualTo(WaybillStatus.COMPLETED);

        // Одометр перенесён обратно в мастер-данные при закрытии (сервисный вызов реально произошёл).
        org.mockito.Mockito.verify(masterData).updateVehicleOdometer(VEHICLE_ID, odometerExit + 120);

        // Полный статусный журнал (append-only) реально записан в БД: DRAFT -> CREATED ->
        // AWAITING_PAYMENT -> PAID -> READY -> ISSUED -> ACTIVE -> RETURNED -> COMPLETED.
        var journal = eventRepository.findByWaybillIdOrderByCreatedAt(id);
        assertThat(journal).extracting(tj.mintrans.epd.waybill.domain.WaybillStatusEvent::getToStatus)
                .containsExactly(
                        WaybillStatus.DRAFT.name(), WaybillStatus.CREATED.name(), WaybillStatus.AWAITING_PAYMENT.name(),
                        WaybillStatus.PAID.name(), WaybillStatus.READY.name(), WaybillStatus.ISSUED.name(),
                        WaybillStatus.ACTIVE.name(), WaybillStatus.RETURNED.name(), WaybillStatus.COMPLETED.name());
    }

    private Waybill fresh(UUID id) {
        return waybillRepository.findById(id).orElseThrow();
    }
}
