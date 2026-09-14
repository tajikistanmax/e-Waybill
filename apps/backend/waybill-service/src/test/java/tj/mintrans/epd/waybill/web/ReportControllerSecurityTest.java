package tj.mintrans.epd.waybill.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tj.mintrans.epd.waybill.config.SecurityConfig;
import tj.mintrans.epd.waybill.print.ReportXlsxWriter;
import tj.mintrans.epd.waybill.service.InspectionJournalService;
import tj.mintrans.epd.waybill.service.RegionalReportService;
import tj.mintrans.epd.waybill.service.ReportService;
import tj.mintrans.epd.waybill.service.WaybillReportService;

import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Права доступа к отчётам ({@code @PreAuthorize} на {@link ReportController}).
 *
 * <p>Прямая защита от найденного вручную бага: класс-уровень {@code @PreAuthorize}
 * не содержал ACCOUNTANT, и бухгалтер получал 403 на своей же стартовой странице.
 * Каждая ячейка матрицы «роль → отчёт» закреплена тестом.</p>
 *
 * <p>«Доступ разрешён» проверяется как «статус НЕ 403»: сервисы замоканы, реальный
 * 2xx/4xx/5xx неважен — важно, что запрос не отклонён по правам.</p>
 */
@WebMvcTest(ReportController.class)
@Import(SecurityConfig.class)
class ReportControllerSecurityTest {

    @Autowired MockMvc mvc;

    @MockitoBean ReportService reports;
    @MockitoBean WaybillReportService typedReports;
    @MockitoBean RegionalReportService regionalReports;
    @MockitoBean InspectionJournalService journals;
    @MockitoBean ReportXlsxWriter xlsx;
    // JwtDecoder не должен ходить в Keycloak — jwt()-постпроцессор кладёт токен напрямую.
    @MockitoBean JwtDecoder jwtDecoder;

    private static final String P = "?from=2026-01-01&to=2026-12-31";

    /** Токен роли: realm-роль превращается в ROLE_<имя>, как боевой конвертер realmRoles(). */
    private static RequestPostProcessor as(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    // --- Сводка: операционные, управляющие, надзор, бухгалтер — доступ; аутсорс-АРМ и водитель — нет.

    @ParameterizedTest
    @ValueSource(strings = {"DISPATCHER", "ACCOUNTANT", "COMPANY_ADMIN", "BRANCH_ADMIN",
            "SYSTEM_ADMIN", "MINTRANS_ANALYST", "INSPECTOR"})
    void summaryAllowed(String role) throws Exception {
        mvc.perform(get("/api/v1/reports/summary" + P).with(as(role)))
                .andExpect(status().is(not(403)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DOCTOR", "MECHANIC", "DRIVER", "FUEL_STATION"})
    void summaryForbidden(String role) throws Exception {
        mvc.perform(get("/api/v1/reports/summary" + P).with(as(role)))
                .andExpect(status().isForbidden());
    }

    // --- Экономика перевозчика (топливо): инспектору закрыто, бухгалтеру открыто.

    @Test
    void fuelForbiddenForInspector() throws Exception {
        mvc.perform(get("/api/v1/reports/fuel" + P).with(as("INSPECTOR")))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCOUNTANT", "DISPATCHER", "COMPANY_ADMIN", "SYSTEM_ADMIN"})
    void fuelAllowed(String role) throws Exception {
        mvc.perform(get("/api/v1/reports/fuel" + P).with(as(role)))
                .andExpect(status().is(not(403)));
    }

    // --- Сводные отчёты Минтранса: только надзор и админ платформы.

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "MINTRANS_ANALYST"})
    void regionalAllowed(String role) throws Exception {
        mvc.perform(get("/api/v1/reports/regional" + P).with(as(role)))
                .andExpect(status().is(not(403)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCOUNTANT", "COMPANY_ADMIN", "BRANCH_ADMIN", "DISPATCHER", "INSPECTOR"})
    void regionalForbidden(String role) throws Exception {
        mvc.perform(get("/api/v1/reports/regional" + P).with(as(role)))
                .andExpect(status().isForbidden());
    }

    // --- Журналы предрейсового контроля: инспектору открыто (надзор).

    @Test
    void mechanicJournalAllowedForInspector() throws Exception {
        mvc.perform(get("/api/v1/reports/journal/mechanic" + P).with(as("INSPECTOR")))
                .andExpect(status().is(not(403)));
    }

    // --- Аноним без токена — 401.

    @Test
    void anonymousUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/reports/summary" + P))
                .andExpect(status().isUnauthorized());
    }
}
