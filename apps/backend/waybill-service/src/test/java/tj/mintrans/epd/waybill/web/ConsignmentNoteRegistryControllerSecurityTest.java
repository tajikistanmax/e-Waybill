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
import tj.mintrans.epd.waybill.print.WaybillPrintService;
import tj.mintrans.epd.waybill.service.ConsignmentNoteRegistryService;

import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Реестр борхатов: Минтранс, перевозчик, грузоотправитель/экспедитор — да; врач, механик, водитель, таможня, инспектор — нет. */
@WebMvcTest(ConsignmentNoteRegistryController.class)
@Import(SecurityConfig.class)
class ConsignmentNoteRegistryControllerSecurityTest {

    @Autowired MockMvc mvc;
    @MockitoBean ConsignmentNoteRegistryService registry;
    @MockitoBean WaybillPrintService print;
    @MockitoBean JwtDecoder jwtDecoder;

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "MINTRANS_ANALYST", "DISPATCHER", "COMPANY_ADMIN", "BRANCH_ADMIN",
            "ACCOUNTANT", "CLIENT_SENDER", "CLIENT_FORWARDER"})
    void allowed(String role) throws Exception {
        mvc.perform(get("/api/v1/consignment-notes").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                .andExpect(status().is(not(403)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DOCTOR", "MECHANIC", "DRIVER", "CUSTOMS_OFFICER", "INSPECTOR", "FUEL_STATION"})
    void forbidden(String role) throws Exception {
        mvc.perform(get("/api/v1/consignment-notes").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                .andExpect(status().isForbidden());
    }
}
