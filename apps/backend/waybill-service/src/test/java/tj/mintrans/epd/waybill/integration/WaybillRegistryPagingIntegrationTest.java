package tj.mintrans.epd.waybill.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tj.mintrans.epd.waybill.client.MasterDataClient;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.service.WaybillRegistryService;
import tj.mintrans.epd.waybill.service.WaybillRegistryService.Filter;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIGRATION.md §8.4 — пагинация реестра против РЕАЛЬНОГО Postgres (не мока): граница размера
 * страницы, последняя страница, выход за последнюю — то, что мок-тест {@code WaybillRegistryServicePagingTest}
 * может только смоделировать, а здесь проверяется настоящим {@code findAll(spec, pageable)}.
 *
 * <p>Без заголовка JWT в контексте {@link tj.mintrans.epd.waybill.config.CurrentUser#isJwtAuthenticated()}
 * = false → {@link tj.mintrans.epd.waybill.config.TenantScope#isBounded()} = false — область не
 * ограничена (как у платформенной роли), что и нужно: видим ВСЕ вставленные строки без шума
 * тенант-скоупинга. {@code epd.registry.max-page-size=5} — лимит РАЗМЕРА страницы, чтобы граница
 * (запрошено 50, но настроен потолок 5) была достижима без вставки тысяч строк.</p>
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = "epd.registry.max-page-size=5")
class WaybillRegistryPagingIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // MedicalDataCrypto требует 256-битный ключ без dev-дефолта (ИБ-13.1.3) — тест не трогает
        // медицинские данные, но бин создаётся при старте контекста; тестовый ключ, не секрет прода.
        registry.add("epd.meddata.encryption-key", () -> "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=");
    }

    private static final String ORG_RMA = "900000001";
    private static final int TOTAL_ROWS = 7;

    @Autowired
    WaybillRepository waybillRepository;
    @Autowired
    WaybillRegistryService registry;

    @MockitoBean
    MasterDataClient masterData;
    @MockitoBean
    KafkaTemplate<String, String> kafkaTemplate;

    private static final Filter FILTER_FOR_ORG =
            new Filter(ORG_RMA, null, null, null, null, null, null, null, null, null, null, false);

    @BeforeEach
    void seedRows() {
        // Контейнер Postgres общий на весь класс (Testcontainers singleton-per-class) — без очистки
        // строки предыдущего теста накапливались бы (7, 14, 21…) и ломали бы проверку totalElements.
        waybillRepository.deleteAll();
        for (int i = 0; i < TOTAL_ROWS; i++) {
            var wb = new Waybill();
            wb.setWaybillType(WaybillType.WB_TAXI);
            wb.setOrganizationRma(ORG_RMA);
            wb.setVehicleRegNumber("TEST" + i);
            wb.setDriverRma("800000000");
            waybillRepository.save(wb);
        }
    }

    @AfterEach
    void cleanup() {
        waybillRepository.deleteAll();
    }

    @Test
    void firstPage_returnsRequestedSizeAndRealTotals() {
        var result = registry.page(FILTER_FOR_ORG, 0, 3);
        assertThat(result.content()).hasSize(3);
        assertThat(result.totalElements()).isEqualTo(TOTAL_ROWS);
        assertThat(result.totalPages()).isEqualTo(3); // ceil(7/3)
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(3);
    }

    @Test
    void lastPage_returnsRemainder() {
        // 7 строк, размер 3: страницы 0,1 — по 3; страница 2 (последняя) — остаток из 1.
        var result = registry.page(FILTER_FOR_ORG, 2, 3);
        assertThat(result.content()).hasSize(1);
        assertThat(result.totalElements()).isEqualTo(TOTAL_ROWS);
        assertThat(result.totalPages()).isEqualTo(3);
    }

    @Test
    void pageBeyondLast_returnsEmptyContentNotError_withRealTotals() {
        // Страница ЗА последней (3-я при totalPages=3, индексация с 0) — пустой content,
        // но totalElements/totalPages ПРАВДИВЫ (не 0/0 — это было бы неотличимо от "нет доступа").
        var result = registry.page(FILTER_FOR_ORG, 3, 3);
        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isEqualTo(TOTAL_ROWS);
        assertThat(result.totalPages()).isEqualTo(3);
    }

    @Test
    void pageSizeClampedToConfiguredMax_evenWhenMoreRowsExist() {
        // epd.registry.max-page-size=5 (свойство теста) — клиент просит size=50 на 7 реальных
        // строках; сервис не отдаёт больше 5 за раз (защита от OOM), но totalElements показывает
        // все 7 — общее число записей НЕ ограничено, дойти до второй страницы можно.
        var firstPage = registry.page(FILTER_FOR_ORG, 0, 50);
        assertThat(firstPage.content()).hasSize(5);
        assertThat(firstPage.size()).isEqualTo(5);
        assertThat(firstPage.totalElements()).isEqualTo(TOTAL_ROWS);
        assertThat(firstPage.totalPages()).isEqualTo(2); // ceil(7/5)

        var secondPage = registry.page(FILTER_FOR_ORG, 1, 50);
        assertThat(secondPage.content()).hasSize(2); // остаток 7-5
    }
}
