package tj.mintrans.epd.masterdata.web;

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
import tj.mintrans.epd.masterdata.config.CurrentUser;
import tj.mintrans.epd.masterdata.config.SecurityConfig;
import tj.mintrans.epd.masterdata.repository.CargoRepository;
import tj.mintrans.epd.masterdata.repository.ClientRepository;
import tj.mintrans.epd.masterdata.repository.CoefficientRepository;
import tj.mintrans.epd.masterdata.repository.FuelNormRepository;
import tj.mintrans.epd.masterdata.repository.RouteRepository;
import tj.mintrans.epd.masterdata.repository.TariffRepository;
import tj.mintrans.epd.masterdata.service.AuditService;

import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Права на новый справочник «Груз» ({@link DictionaryController#upsertCargo}).
 *
 * <p>Cargo — платформенный справочник (эталонная {@code cargos} не имеет колонки организации),
 * но по явному решению задачи правят им COMPANY_ADMIN/SYSTEM_ADMIN — та же роль-модель, что и
 * у Route/Client (в отличие от fuel-norms/coefficients/tariffs, которые только SYSTEM_ADMIN).</p>
 */
@WebMvcTest(DictionaryController.class)
@Import(SecurityConfig.class)
class DictionaryControllerCargoSecurityTest {

    @Autowired MockMvc mvc;

    @MockitoBean RouteRepository routes;
    @MockitoBean ClientRepository clients;
    @MockitoBean FuelNormRepository fuelNorms;
    @MockitoBean CoefficientRepository coefficients;
    @MockitoBean TariffRepository tariffs;
    @MockitoBean CargoRepository cargos;
    @MockitoBean CurrentUser currentUser;
    @MockitoBean AuditService audit;
    @MockitoBean JwtDecoder jwtDecoder;

    private static RequestPostProcessor as(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static final String CARGO_BODY = "{\"name\":\"Цемент навалом\"}";

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "COMPANY_ADMIN"})
    void postAllowed(String role) throws Exception {
        mvc.perform(post("/api/v1/dictionaries/cargos").with(as(role))
                        .contentType(MediaType.APPLICATION_JSON).content(CARGO_BODY))
                .andExpect(status().is(not(403)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DISPATCHER", "DRIVER", "MINTRANS_ANALYST", "INSPECTOR"})
    void postForbidden(String role) throws Exception {
        mvc.perform(post("/api/v1/dictionaries/cargos").with(as(role))
                        .contentType(MediaType.APPLICATION_JSON).content(CARGO_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void getOpenToAnyAuthenticatedRole() throws Exception {
        mvc.perform(get("/api/v1/dictionaries/cargos").with(as("DRIVER")))
                .andExpect(status().is(not(403)));
    }

    @Test
    void anonymousUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/dictionaries/cargos")).andExpect(status().isUnauthorized());
    }
}
