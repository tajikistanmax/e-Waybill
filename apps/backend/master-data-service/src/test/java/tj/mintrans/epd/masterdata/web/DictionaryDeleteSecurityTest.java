package tj.mintrans.epd.masterdata.web;

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
import tj.mintrans.epd.masterdata.config.CurrentUser;
import tj.mintrans.epd.masterdata.config.SecurityConfig;
import tj.mintrans.epd.masterdata.domain.Route;
import tj.mintrans.epd.masterdata.repository.CargoRepository;
import tj.mintrans.epd.masterdata.repository.ClientRepository;
import tj.mintrans.epd.masterdata.repository.CoefficientRepository;
import tj.mintrans.epd.masterdata.repository.FuelNormRepository;
import tj.mintrans.epd.masterdata.repository.RouteRepository;
import tj.mintrans.epd.masterdata.repository.TariffRepository;
import tj.mintrans.epd.masterdata.service.AuditService;

import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Удаление записей справочников (MIGRATION.md 8.11): организационные (маршруты/клиенты/грузы) —
 * администратор компании и системный; национальные (нормы/коэффициенты/тарифы) — только системный.
 * Тенант не может удалить запись чужой организации (404, не раскрываем существование).
 */
@WebMvcTest(DictionaryController.class)
@Import(SecurityConfig.class)
class DictionaryDeleteSecurityTest {

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

    private static final UUID ID = UUID.randomUUID();

    private static RequestPostProcessor as(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private Route route(String org) {
        Route r = new Route();
        r.setOrganizationRma(org);
        r.setNumber("3");
        r.setName("Вокзал — Аэропорт");
        return r;
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "COMPANY_ADMIN"})
    void routeDeleteAllowedForAdmins(String role) throws Exception {
        when(currentUser.isTenantScoped()).thenReturn(false);
        when(routes.findById(ID)).thenReturn(Optional.of(route("025680800")));
        mvc.perform(delete("/api/v1/dictionaries/routes/" + ID).with(as(role)))
                .andExpect(status().isNoContent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DISPATCHER", "DRIVER", "MINTRANS_ANALYST", "INSPECTOR", "ACCOUNTANT"})
    void routeDeleteForbiddenForOthers(String role) throws Exception {
        mvc.perform(delete("/api/v1/dictionaries/routes/" + ID).with(as(role)))
                .andExpect(status().isForbidden());
        verify(routes, never()).delete(any());
    }

    @Test
    void tenantCannotDeleteForeignRoute() throws Exception {
        when(currentUser.isTenantScoped()).thenReturn(true);
        when(currentUser.organizationRma()).thenReturn(Optional.of("111111111"));
        when(routes.findById(ID)).thenReturn(Optional.of(route("025680800")));
        mvc.perform(delete("/api/v1/dictionaries/routes/" + ID).with(as("COMPANY_ADMIN")))
                .andExpect(status().isNotFound());
        verify(routes, never()).delete(any());
    }

    @Test
    void missingRecordIsNotFound() throws Exception {
        when(currentUser.isTenantScoped()).thenReturn(false);
        when(routes.findById(ID)).thenReturn(Optional.empty());
        mvc.perform(delete("/api/v1/dictionaries/routes/" + ID).with(as("SYSTEM_ADMIN")))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPANY_ADMIN", "DISPATCHER"})
    void nationalDictionaryDeleteOnlyForSystemAdmin(String role) throws Exception {
        mvc.perform(delete("/api/v1/dictionaries/fuel-norms/" + ID).with(as(role)))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/dictionaries/coefficients/" + ID).with(as(role)))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/dictionaries/tariffs/" + ID).with(as(role)))
                .andExpect(status().isForbidden());
    }

    @Test
    void systemAdminReachesNationalDictionaryDelete() throws Exception {
        when(fuelNorms.findById(ID)).thenReturn(Optional.empty());
        mvc.perform(delete("/api/v1/dictionaries/fuel-norms/" + ID).with(as("SYSTEM_ADMIN")))
                .andExpect(status().is(not(403)));
    }
}
