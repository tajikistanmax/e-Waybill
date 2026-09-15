package tj.mintrans.epd.waybill.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tj.mintrans.epd.waybill.calc.WaybillCalcAssembler;
import tj.mintrans.epd.waybill.config.CurrentUser;
import tj.mintrans.epd.waybill.config.TenantScope;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.repository.WaybillStatusEventRepository;
import tj.mintrans.epd.waybill.repository.WaybillTitleRepository;
import tj.mintrans.epd.waybill.config.SecurityConfig;
import tj.mintrans.epd.waybill.service.FuelCalculationService;
import tj.mintrans.epd.waybill.service.QrTokenService;
import tj.mintrans.epd.waybill.service.WaybillService;

import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Права доступа к операциям жизненного цикла путевого листа.
 *
 * <p>Тела запросов валидны по {@code @Valid} — иначе 400 сработал бы до {@code @PreAuthorize}
 * и тест проверял бы не то. Бизнес-валидация в сервисе не выполняется: сервис замокан,
 * важно лишь, отклонён ли запрос по правам.</p>
 */
@WebMvcTest(WaybillController.class)
@Import(SecurityConfig.class)
class WaybillControllerSecurityTest {

    @Autowired MockMvc mvc;

    @MockitoBean WaybillService service;
    @MockitoBean WaybillRepository waybills;
    @MockitoBean WaybillTitleRepository titles;
    @MockitoBean WaybillStatusEventRepository events;
    @MockitoBean QrTokenService qr;
    @MockitoBean FuelCalculationService fuelCalculation;
    @MockitoBean WaybillCalcAssembler waybillCalc;
    @MockitoBean CurrentUser currentUser;
    @MockitoBean TenantScope tenantScope;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final String ID = "/00000000-0000-0000-0000-000000000000";

    private static RequestPostProcessor as(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String path, String role, String body) throws Exception {
        return mvc.perform(post("/api/v1/waybills" + path)
                .with(as(role)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // --- Создание ПЛ: только диспетчер и админ платформы.
    private static final String CREATE = """
            {"waybillType":"WB_TAXI","organizationRma":"025680800","vehicleRegNumber":"5500TJ33","driverRma":"461930031"}""";

    @ParameterizedTest
    @ValueSource(strings = {"DISPATCHER", "SYSTEM_ADMIN"})
    void createAllowed(String role) throws Exception {
        postJson("", role, CREATE).andExpect(status().is(not(403)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCOUNTANT", "DOCTOR", "MECHANIC", "DRIVER", "INSPECTOR",
            "COMPANY_ADMIN", "BRANCH_ADMIN", "MINTRANS_ANALYST", "FUEL_STATION"})
    void createForbidden(String role) throws Exception {
        postJson("", role, CREATE).andExpect(status().isForbidden());
    }

    // --- Т1 (выпуск): диспетчер.
    private static final String T1 = """
            {"dispatcherRma":"333333333","validityDays":1}""";

    @Test
    void signT1AllowedForDispatcher() throws Exception {
        postJson(ID + "/titles/t1", "DISPATCHER", T1).andExpect(status().is(not(403)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DOCTOR", "MECHANIC", "ACCOUNTANT", "INSPECTOR"})
    void signT1Forbidden(String role) throws Exception {
        postJson(ID + "/titles/t1", role, T1).andExpect(status().isForbidden());
    }

    // --- Т2 (медосмотр): врач.
    private static final String MED = """
            {"employeeRma":"111111111","passed":true}""";

    @Test
    void confirmMedAllowedForDoctor() throws Exception {
        postJson(ID + "/confirm-med", "DOCTOR", MED).andExpect(status().is(not(403)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DISPATCHER", "MECHANIC", "INSPECTOR"})
    void confirmMedForbidden(String role) throws Exception {
        postJson(ID + "/confirm-med", role, MED).andExpect(status().isForbidden());
    }

    // --- Т3 (техконтроль): механик.
    private static final String TECH = """
            {"employeeRma":"222222222","passed":true}""";

    @Test
    void confirmTechAllowedForMechanic() throws Exception {
        postJson(ID + "/confirm-tech", "MECHANIC", TECH).andExpect(status().is(not(403)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DISPATCHER", "DOCTOR", "INSPECTOR"})
    void confirmTechForbidden(String role) throws Exception {
        postJson(ID + "/confirm-tech", role, TECH).andExpect(status().isForbidden());
    }

    // --- Оплата: бухгалтер, админ компании, админ платформы.
    @ParameterizedTest
    @ValueSource(strings = {"ACCOUNTANT", "COMPANY_ADMIN", "SYSTEM_ADMIN"})
    void confirmPaymentAllowed(String role) throws Exception {
        postJson(ID + "/confirm-payment", role, "{}").andExpect(status().is(not(403)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DISPATCHER", "DOCTOR", "INSPECTOR", "DRIVER"})
    void confirmPaymentForbidden(String role) throws Exception {
        postJson(ID + "/confirm-payment", role, "{}").andExpect(status().isForbidden());
    }

    // --- Блокировка на дорожном контроле: инспектор и админ платформы.
    private static final String BLOCK = """
            {"reasonCode":"NO_MED"}""";

    @ParameterizedTest
    @ValueSource(strings = {"INSPECTOR", "SYSTEM_ADMIN"})
    void blockAllowed(String role) throws Exception {
        postJson(ID + "/block", role, BLOCK).andExpect(status().is(not(403)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DISPATCHER", "COMPANY_ADMIN", "ACCOUNTANT", "DOCTOR"})
    void blockForbidden(String role) throws Exception {
        postJson(ID + "/block", role, BLOCK).andExpect(status().isForbidden());
    }

    // --- Разблокировка: только админ платформы (Минтранс).
    @Test
    void unblockAllowedForSystemAdmin() throws Exception {
        postJson(ID + "/unblock", "SYSTEM_ADMIN", "{\"reason\":\"проверка снята\"}").andExpect(status().is(not(403)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSPECTOR", "COMPANY_ADMIN", "DISPATCHER"})
    void unblockForbidden(String role) throws Exception {
        postJson(ID + "/unblock", role, "{\"reason\":\"x\"}").andExpect(status().isForbidden());
    }

    // --- Аноним — 401.
    @Test
    void anonymousUnauthorized() throws Exception {
        mvc.perform(post("/api/v1/waybills").contentType(MediaType.APPLICATION_JSON).content(CREATE))
                .andExpect(status().isUnauthorized());
    }
}
