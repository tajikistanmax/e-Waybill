package tj.mintrans.epd.waybill.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tj.mintrans.epd.waybill.config.SecurityConfig;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.service.QrTokenService;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Сверка 25.09, G4: замена legacy {@code POST /api/cmr} — по учётке интегратора, а не по токену из кода. */
@WebMvcTest(RefCmrController.class)
@Import(SecurityConfig.class)
class RefCmrControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean WaybillRepository waybills;
    @MockitoBean QrTokenService qr;
    @MockitoBean JwtDecoder jwtDecoder;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor as(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void cmrByNumberWithVerifyLink() throws Exception {
        var wb = new Waybill();
        wb.setWaybillType(WaybillType.WB_TRUCK_INTL);
        wb.setStatus(WaybillStatus.ISSUED);
        wb.setNumber("07-26-02-0000031-5");
        wb.setOrganizationRma("025680800");
        wb.setOrganizationSnapshot(Map.of("name", "Тоҷиктранс"));
        when(waybills.findByNumber("07-26-02-0000031-5")).thenReturn(Optional.of(wb));
        when(qr.sign(any(Waybill.class))).thenReturn("JWS");

        mvc.perform(post("/api/v1/ref/cmr").with(as("API_INTEGRATOR")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cmr_number\":\"07-26-02-0000031-5\",\"token\":\"старый\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value("07-26-02-0000031-5"))
                .andExpect(jsonPath("$.company").value("Тоҷиктранс"))
                .andExpect(jsonPath("$.rma").value("025680800"))
                .andExpect(jsonPath("$.link").value("http://localhost:3000/verify/JWS"));

        mvc.perform(post("/api/v1/ref/cmr?cmr_number=нет").with(as("API_INTEGRATOR")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CMR not found"));
    }

    @Test
    void onlyIntegrator() throws Exception {
        mvc.perform(post("/api/v1/ref/cmr?cmr_number=1").with(as("DISPATCHER")))
                .andExpect(status().isForbidden());
    }
}
