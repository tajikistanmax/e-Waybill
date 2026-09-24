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
import tj.mintrans.epd.masterdata.config.SecurityConfig;
import tj.mintrans.epd.masterdata.config.TenantScope;
import tj.mintrans.epd.masterdata.domain.Driver;
import tj.mintrans.epd.masterdata.domain.Organization;
import tj.mintrans.epd.masterdata.repository.DriverRepository;
import tj.mintrans.epd.masterdata.repository.EmployeeRepository;
import tj.mintrans.epd.masterdata.repository.OrganizationRepository;
import tj.mintrans.epd.masterdata.repository.VehicleRepository;
import tj.mintrans.epd.masterdata.service.AuditService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Перевод субъектов между организациями ({@link SubjectAssignmentController}).
 * Перевозчик закрепляет за собой только свободного субъекта; чужого — 409; открепляет только своего.
 */
@WebMvcTest(SubjectAssignmentController.class)
@Import(SecurityConfig.class)
class SubjectAssignmentSecurityTest {

    @Autowired MockMvc mvc;

    @MockitoBean DriverRepository drivers;
    @MockitoBean VehicleRepository vehicles;
    @MockitoBean EmployeeRepository employees;
    @MockitoBean OrganizationRepository organizations;
    @MockitoBean TenantScope tenantScope;
    @MockitoBean AuditService audit;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean tj.mintrans.epd.masterdata.config.CurrentUser currentUser;
    @MockitoBean tj.mintrans.epd.masterdata.service.MasterDataSourcePolicy sourcePolicy;

    private static final UUID DRIVER_ID = UUID.randomUUID();
    private static final UUID OWN_ORG = UUID.randomUUID();
    private static final UUID OTHER_ORG = UUID.randomUUID();
    private static final String BODY = "{\"organizationRma\":\"025680800\"}";

    private static RequestPostProcessor as(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    /** Идентификатор сущности присваивается в @PrePersist, поэтому в тесте ставим его рефлексией. */
    private static <T> T withId(T entity, UUID id) {
        try {
            var f = entity.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
            return entity;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Organization org(UUID id, String rma, String name) {
        Organization o = new Organization();
        o.setRma(rma);
        o.setName(name);
        return withId(o, id);
    }

    private Driver driver(UUID orgId) {
        Driver d = new Driver();
        d.setRma("461930031");
        d.setFullName("Ахмедзода Зохид");
        d.setOrganizationId(orgId);
        return withId(d, DRIVER_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRIVER", "DOCTOR", "MECHANIC", "ACCOUNTANT", "INSPECTOR", "MINTRANS_ANALYST"})
    void forbiddenForNonAdminRoles(String role) throws Exception {
        mvc.perform(get("/api/v1/subjects/drivers/lookup?key=461930031").with(as(role)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/subjects/drivers/" + DRIVER_ID + "/attach").with(as(role))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verify(drivers, never()).save(any());
    }

    /**
     * Справочник ведёт единая платформа (Настройки → Интеграции, 24.09.2026): ручное прикрепление
     * отклоняется (409), а сама единая платформа (API_INTEGRATOR) прикрепляет и помечает запись.
     */
    @Test
    void unifiedModeBlocksManualAttachButLetsUnifiedPlatformAttach() throws Exception {
        when(organizations.findByRma("025680800")).thenReturn(Optional.of(org(OWN_ORG, "025680800", "Своя")));
        when(drivers.findById(DRIVER_ID)).thenReturn(Optional.of(driver(null)));
        when(drivers.save(any())).thenAnswer(i -> i.getArgument(0));
        org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "единая платформа"))
                .when(sourcePolicy).assertManualAttachAllowed("driver");

        mvc.perform(post("/api/v1/subjects/drivers/" + DRIVER_ID + "/attach").with(as("SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());
        verify(drivers, never()).save(any());

        when(currentUser.hasRole("API_INTEGRATOR")).thenReturn(true);
        mvc.perform(post("/api/v1/subjects/drivers/" + DRIVER_ID + "/attach").with(as("API_INTEGRATOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
        var saved = org.mockito.ArgumentCaptor.forClass(Driver.class);
        verify(drivers).save(saved.capture());
        org.assertj.core.api.Assertions.assertThat(saved.getValue().getSource()).isEqualTo("UNIFIED");
    }

    @Test
    void lookupShowsOwningOrganization() throws Exception {
        when(drivers.findByRma("461930031")).thenReturn(Optional.of(driver(OTHER_ORG)));
        when(organizations.findById(OTHER_ORG)).thenReturn(Optional.of(org(OTHER_ORG, "111111111", "Чужая компания")));
        mvc.perform(get("/api/v1/subjects/drivers/lookup?key=461930031").with(as("COMPANY_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("461930031"))
                .andExpect(jsonPath("$.attached").value(true))
                .andExpect(jsonPath("$.organizationRma").value("111111111"));
    }

    @Test
    void lookupOfUnknownKeyIsNotFound() throws Exception {
        when(drivers.findByRma("000000000")).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/subjects/drivers/lookup?key=000000000").with(as("SYSTEM_ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void carrierCannotTakeForeignSubject() throws Exception {
        when(tenantScope.isBounded()).thenReturn(true);
        when(tenantScope.canWrite("025680800")).thenReturn(true);
        when(organizations.findByRma("025680800")).thenReturn(Optional.of(org(OWN_ORG, "025680800", "Своя компания")));
        when(drivers.findById(DRIVER_ID)).thenReturn(Optional.of(driver(OTHER_ORG)));
        mvc.perform(post("/api/v1/subjects/drivers/" + DRIVER_ID + "/attach").with(as("COMPANY_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());
        verify(drivers, never()).save(any());
    }

    @Test
    void carrierAttachesFreeSubject() throws Exception {
        when(tenantScope.isBounded()).thenReturn(true);
        when(tenantScope.canWrite("025680800")).thenReturn(true);
        when(organizations.findByRma("025680800")).thenReturn(Optional.of(org(OWN_ORG, "025680800", "Своя компания")));
        when(organizations.findById(OWN_ORG)).thenReturn(Optional.of(org(OWN_ORG, "025680800", "Своя компания")));
        when(drivers.findById(DRIVER_ID)).thenReturn(Optional.of(driver(null)));
        when(drivers.save(any())).thenAnswer(inv -> inv.getArgument(0));
        mvc.perform(post("/api/v1/subjects/drivers/" + DRIVER_ID + "/attach").with(as("COMPANY_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attached").value(true))
                .andExpect(jsonPath("$.organizationRma").value("025680800"));
    }

    @Test
    void detachOfForeignSubjectIsForbiddenAndOwnIsAllowed() throws Exception {
        when(tenantScope.isBounded()).thenReturn(true);
        when(tenantScope.organizationIds()).thenReturn(List.of(OWN_ORG));
        when(drivers.findById(DRIVER_ID)).thenReturn(Optional.of(driver(OTHER_ORG)));
        mvc.perform(post("/api/v1/subjects/drivers/" + DRIVER_ID + "/detach").with(as("COMPANY_ADMIN")))
                .andExpect(status().isForbidden());

        when(drivers.findById(DRIVER_ID)).thenReturn(Optional.of(driver(OWN_ORG)));
        when(organizations.findById(OWN_ORG)).thenReturn(Optional.of(org(OWN_ORG, "025680800", "Своя компания")));
        when(drivers.save(any())).thenAnswer(inv -> inv.getArgument(0));
        mvc.perform(post("/api/v1/subjects/drivers/" + DRIVER_ID + "/detach").with(as("COMPANY_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attached").value(false));
    }

    @Test
    void detachOfFreeSubjectIsUnprocessable() throws Exception {
        when(tenantScope.isBounded()).thenReturn(false);
        when(drivers.findById(DRIVER_ID)).thenReturn(Optional.of(driver(null)));
        mvc.perform(post("/api/v1/subjects/drivers/" + DRIVER_ID + "/detach").with(as("SYSTEM_ADMIN")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void systemAdminMovesSubjectBetweenOrganizations() throws Exception {
        when(tenantScope.isBounded()).thenReturn(false);
        when(organizations.findByRma("025680800")).thenReturn(Optional.of(org(OWN_ORG, "025680800", "Новая компания")));
        when(organizations.findById(OWN_ORG)).thenReturn(Optional.of(org(OWN_ORG, "025680800", "Новая компания")));
        when(organizations.findById(OTHER_ORG)).thenReturn(Optional.of(org(OTHER_ORG, "111111111", "Прежняя компания")));
        when(drivers.findById(DRIVER_ID)).thenReturn(Optional.of(driver(OTHER_ORG)));
        when(drivers.save(any())).thenAnswer(inv -> inv.getArgument(0));
        mvc.perform(post("/api/v1/subjects/drivers/" + DRIVER_ID + "/attach").with(as("SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is(not(409)))
                .andExpect(jsonPath("$.organizationRma").value("025680800"));
    }
}
