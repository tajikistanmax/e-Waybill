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
import tj.mintrans.epd.waybill.config.SecurityConfig;
import tj.mintrans.epd.waybill.service.RefChannelService;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MIGRATION.md 9.8 — B2B-канал ref/*: только интеграция/админ; неизвестная форма → 404; confirm отвечает true. */
@WebMvcTest(RefController.class)
@Import(SecurityConfig.class)
class RefControllerSecurityTest {

    @Autowired MockMvc mvc;
    @MockitoBean RefChannelService service;
    @MockitoBean JwtDecoder jwtDecoder;

    private static RequestPostProcessor as(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @ParameterizedTest
    @ValueSource(strings = {"API_INTEGRATOR", "SYSTEM_ADMIN"})
    void indexAllowedForIntegration(String role) throws Exception {
        when(service.list(any(), any())).thenReturn(new RefChannelService.PageResult(1, List.of(), 10, 0, 1));
        mvc.perform(get("/api/v1/ref/waybill1ad?organization_rma=025680800").with(as(role))).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DISPATCHER", "COMPANY_ADMIN", "DRIVER", "INSPECTOR", "CLIENT_SENDER"})
    void forbiddenForOtherRoles(String role) throws Exception {
        mvc.perform(get("/api/v1/ref/waybill1ad").with(as(role))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/ref/waybill/confirm").with(as(role)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"API_INTEGRATOR"})
    void unknownFormIs404AndConfirmReturnsTrue(String role) throws Exception {
        mvc.perform(get("/api/v1/ref/waybill9").with(as(role))).andExpect(status().isNotFound());
        when(service.confirm(any())).thenReturn(true);
        mvc.perform(post("/api/v1/ref/waybill/confirm").with(as(role)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organization_rma\":\"025680800\",\"waybill_type\":1,\"waybill_id\":\"x\",\"employee_rma\":\"111111111\"}"))
                .andExpect(status().isCreated()).andExpect(content().string("true"));
    }
}
