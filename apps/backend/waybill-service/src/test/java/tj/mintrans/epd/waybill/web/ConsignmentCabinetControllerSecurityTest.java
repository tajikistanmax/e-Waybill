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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tj.mintrans.epd.waybill.config.SecurityConfig;
import tj.mintrans.epd.waybill.service.ConsignmentCabinetService;

import java.util.List;

import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MIGRATION.md §1.1/§3.11 — кабинет накладных: доступ только внешним ролям; таможенное подтверждение — только таможеннику.
 */
@WebMvcTest(ConsignmentCabinetController.class)
@Import(SecurityConfig.class)
class ConsignmentCabinetControllerSecurityTest {

    @Autowired MockMvc mvc;
    @MockitoBean ConsignmentCabinetService cabinet;
    @MockitoBean JwtDecoder jwtDecoder;

    private static RequestPostProcessor as(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLIENT_SENDER", "CLIENT_FORWARDER", "CUSTOMS_OFFICER"})
    void listAllowedForExternalRoles(String role) throws Exception {
        when(cabinet.list(any(), anyInt(), anyInt()))
                .thenReturn(new ConsignmentCabinetService.PageResult(List.of(), 0, 20, 0, 0, "none"));
        mvc.perform(get("/api/v1/consignments").with(as(role))).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DISPATCHER", "COMPANY_ADMIN", "SYSTEM_ADMIN", "DRIVER", "INSPECTOR", "ACCOUNTANT", "API_INTEGRATOR"})
    void listForbiddenForOtherRoles(String role) throws Exception {
        mvc.perform(get("/api/v1/consignments").with(as(role))).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLIENT_SENDER", "CLIENT_FORWARDER", "SYSTEM_ADMIN"})
    void customsConfirmOnlyForCustomsOfficer(String role) throws Exception {
        mvc.perform(post("/api/v1/consignments/00000000-0000-0000-0000-000000000000/customs-confirm").with(as(role)))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMS_OFFICER"})
    void customsConfirmAllowed(String role) throws Exception {
        mvc.perform(post("/api/v1/consignments/00000000-0000-0000-0000-000000000000/customs-confirm").with(as(role)))
                .andExpect(status().is(not(403)));
    }
}
