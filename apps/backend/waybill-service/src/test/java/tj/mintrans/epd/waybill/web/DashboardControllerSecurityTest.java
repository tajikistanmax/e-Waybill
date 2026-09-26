package tj.mintrans.epd.waybill.web;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tj.mintrans.epd.waybill.config.SecurityConfig;
import tj.mintrans.epd.waybill.service.DashboardService;

import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Показатели панели: управляющие и надзорные роли — да; водитель, врач, механик, внешние кабинеты — нет. */
@WebMvcTest(DashboardController.class)
@Import(SecurityConfig.class)
class DashboardControllerSecurityTest {

    @Autowired MockMvc mvc;
    @MockitoBean DashboardService dashboard;
    @MockitoBean JwtDecoder jwtDecoder;

    @ParameterizedTest
    @ValueSource(strings = {"DISPATCHER", "ACCOUNTANT", "COMPANY_ADMIN", "BRANCH_ADMIN", "SYSTEM_ADMIN", "MINTRANS_ANALYST", "INSPECTOR"})
    void allowed(String role) throws Exception {
        mvc.perform(get("/api/v1/dashboard").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                .andExpect(status().is(not(403)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRIVER", "DOCTOR", "MECHANIC", "FUEL_STATION", "CLIENT_SENDER", "CUSTOMS_OFFICER"})
    void forbidden(String role) throws Exception {
        mvc.perform(get("/api/v1/dashboard").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                .andExpect(status().isForbidden());
    }
}
