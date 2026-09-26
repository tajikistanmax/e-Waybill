package tj.mintrans.epd.waybill.web;

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
import tj.mintrans.epd.waybill.client.MasterDataClient;
import tj.mintrans.epd.waybill.config.SecurityConfig;
import tj.mintrans.epd.waybill.config.TenantScope;
import tj.mintrans.epd.waybill.repository.GpsPingRepository;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.service.GpsEventService;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MIGRATION.md 9.7 / 8.9 — GPS-события: регистрация только интеграцией (legacy company.jwt:2 — один сервисный
 * аккаунт), журнал — диспетчер/компания/платформенные роли; неверное состояние — 422.
 */
@WebMvcTest(GpsController.class)
@Import(SecurityConfig.class)
class GpsEventControllerSecurityTest {

    @Autowired MockMvc mvc;
    @MockitoBean GpsPingRepository pings;
    @MockitoBean WaybillRepository waybills;
    @MockitoBean MasterDataClient masterData;
    @MockitoBean TenantScope tenantScope;
    @MockitoBean GpsEventService gpsEvents;
    @MockitoBean JwtDecoder jwtDecoder;

    private static RequestPostProcessor as(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static final String BODY = "{\"registration_number\":\"0114TJ01\",\"state\":\"enter_into_route\",\"direction\":\"A\"}";

    @ParameterizedTest
    @ValueSource(strings = {"API_INTEGRATOR", "SYSTEM_ADMIN"})
    void registerAllowedForIntegration(String role) throws Exception {
        when(gpsEvents.register(any())).thenReturn(new GpsEventService.Result(true, null, null, null, null));
        // Как legacy GpsDataController::store (G3): 200 и {"success": true}.
        mvc.perform(post("/api/v1/gps/events").with(as(role)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    /** Выезд из предприятия — маршрут, график и время выезда ПЛ; ошибка — errors по полю (G3). */
    @ParameterizedTest
    @ValueSource(strings = {"API_INTEGRATOR"})
    void legacyBodies(String role) throws Exception {
        when(gpsEvents.register(any())).thenReturn(new GpsEventService.Result(true, null, "12", "06:00-22:00", "06:10"));
        mvc.perform(post("/api/v1/gps/events").with(as(role)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registration_number\":\"0114TJ01\",\"state\":\"exit_from_company\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route_number").value("12"))
                .andExpect(jsonPath("$.schedule").value("06:00-22:00"))
                .andExpect(jsonPath("$.exit_time").value("06:10"));

        when(gpsEvents.register(any())).thenThrow(new tj.mintrans.epd.waybill.web.error.ApiErrors.FieldException(
                "waybill", "Путевой лист для данного транспортного средства не найден."));
        mvc.perform(post("/api/v1/gps/events").with(as(role)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors.waybill[0]").value("Путевой лист для данного транспортного средства не найден."));

        mvc.perform(post("/api/v1/gps/events").with(as(role)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"enter_into_company\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors.registration_number").isArray());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DISPATCHER", "COMPANY_ADMIN", "DRIVER", "INSPECTOR"})
    void registerForbiddenForOthers(String role) throws Exception {
        mvc.perform(post("/api/v1/gps/events").with(as(role)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"API_INTEGRATOR"})
    void unknownStateIs422(String role) throws Exception {
        mvc.perform(post("/api/v1/gps/events").with(as(role)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleRegNumber\":\"0114TJ01\",\"state\":\"teleport\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DISPATCHER", "COMPANY_ADMIN", "BRANCH_ADMIN", "SYSTEM_ADMIN", "INSPECTOR", "MINTRANS_ANALYST"})
    void journalAllowed(String role) throws Exception {
        when(gpsEvents.list(any(), anyInt(), anyInt())).thenReturn(new GpsEventService.PageResult(List.of(), 0, 50, 0, 0));
        mvc.perform(get("/api/v1/gps/events?state=enter_into_route").with(as(role))).andExpect(status().isOk());
    }

    /** Монитор «на линии»: у администратора филиала пункт есть в меню — сервер обязан пускать (23.09.2026). */
    @ParameterizedTest
    @ValueSource(strings = {"DISPATCHER", "COMPANY_ADMIN", "BRANCH_ADMIN", "SYSTEM_ADMIN", "INSPECTOR", "MINTRANS_ANALYST"})
    void liveAllowed(String role) throws Exception {
        when(tenantScope.isBounded()).thenReturn(false);
        when(waybills.findByStatusInOrderByCreatedAtDesc(any())).thenReturn(List.of());
        mvc.perform(get("/api/v1/gps/live").with(as(role))).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRIVER", "ACCOUNTANT", "DOCTOR", "CLIENT_SENDER"})
    void liveForbidden(String role) throws Exception {
        mvc.perform(get("/api/v1/gps/live").with(as(role))).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRIVER", "API_INTEGRATOR", "ACCOUNTANT", "CLIENT_SENDER"})
    void journalForbidden(String role) throws Exception {
        mvc.perform(get("/api/v1/gps/events").with(as(role))).andExpect(status().isForbidden());
    }
}
