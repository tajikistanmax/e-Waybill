package tj.mintrans.epd.masterdata.web;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tj.mintrans.epd.masterdata.config.CurrentUser;
import tj.mintrans.epd.masterdata.config.SecurityConfig;
import tj.mintrans.epd.masterdata.config.TenantScope;
import tj.mintrans.epd.masterdata.domain.Organization;
import tj.mintrans.epd.masterdata.repository.OrganizationRepository;
import tj.mintrans.epd.masterdata.service.AuditService;
import tj.mintrans.epd.masterdata.service.FormFieldPolicy;
import tj.mintrans.epd.masterdata.service.MasterDataSourcePolicy;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Находка 12 AUDIT.md: у 191 организации из старой платформы ИНН некорректный (`123`, `0101`),
 * и карточку нельзя было сохранить вообще. Теперь существующую — можно, новую без 9–10 цифр
 * по-прежнему нельзя.
 */
@WebMvcTest(OrganizationController.class)
@Import(SecurityConfig.class)
class OrganizationLegacyRmaTest {

    @Autowired MockMvc mvc;

    @MockitoBean OrganizationRepository repository;
    @MockitoBean TenantScope tenantScope;
    @MockitoBean AuditService audit;
    @MockitoBean EntityManager em;
    @MockitoBean FormFieldPolicy formFields;
    @MockitoBean CurrentUser currentUser;
    @MockitoBean MasterDataSourcePolicy sourcePolicy;
    @MockitoBean JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        when(repository.findByRma(anyString())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(sourcePolicy.guardManualWrite(anyString(), any(), any(), any())).thenAnswer(i -> i.getArgument(1));
    }

    private org.springframework.test.web.servlet.ResultActions save(String body) throws Exception {
        return mvc.perform(post("/api/v1/organizations")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static Organization legacy(String rma, String parent) {
        var o = new Organization();
        o.setRma(rma);
        o.setParentRma(parent);
        o.setName("ҶДММ «Сайёҳон»");
        return o;
    }

    @Test
    void newOrganizationStillNeedsNineOrTenDigits() throws Exception {
        save("{\"rma\":\"123\",\"name\":\"Новая\"}").andExpect(status().isUnprocessableEntity());
        verify(repository, never()).save(any());
    }

    @Test
    void existingLegacyOrganizationCanBeSaved() throws Exception {
        when(repository.findByRma("123")).thenReturn(Optional.of(legacy("123", null)));
        save("{\"rma\":\"123\",\"name\":\"ҶДММ «Сайёҳон»\",\"phone\":\"+992900000000\"}").andExpect(status().isOk());
        verify(repository).save(any());
    }

    @Test
    void legacyParentKeptButNewParentMustBeValid() throws Exception {
        when(repository.findByRma("122")).thenReturn(Optional.of(legacy("122", "123")));
        save("{\"rma\":\"122\",\"parentRma\":\"123\",\"name\":\"Филиал\"}").andExpect(status().isOk());
        save("{\"rma\":\"122\",\"parentRma\":\"12\",\"name\":\"Филиал\"}").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void validNewOrganizationIsCreated() throws Exception {
        save("{\"rma\":\"025680800\",\"name\":\"Новая\",\"subjectType\":\"IP\",\"typeCompany\":2}")
                .andExpect(status().isCreated());
    }

    @Test
    void newOrganizationRequiresAnExplicitSubjectType() throws Exception {
        save("{\"rma\":\"025680800\",\"name\":\"Новая\"}").andExpect(status().isUnprocessableEntity());
        verify(repository, never()).save(any());
    }

    @Test
    void integratorMayOmitSubjectTypeForBackwardCompatibility() throws Exception {
        org.mockito.Mockito.when(currentUser.hasRole("API_INTEGRATOR")).thenReturn(true);
        mvc.perform(post("/api/v1/organizations")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_API_INTEGRATOR")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rma\":\"025680800\",\"name\":\"Новая\"}"))
                .andExpect(status().isCreated());
        var saved = org.mockito.ArgumentCaptor.forClass(Organization.class);
        verify(repository).save(saved.capture());
        org.junit.jupiter.api.Assertions.assertEquals("LEGAL", saved.getValue().getSubjectType());
    }

    @Test
    void subjectTypeIsPersistedOnCreate() throws Exception {
        save("{\"rma\":\"025680800\",\"name\":\"Новая\",\"subjectType\":\"PHYSICAL\"}")
                .andExpect(status().isCreated());
        var saved = org.mockito.ArgumentCaptor.forClass(Organization.class);
        verify(repository).save(saved.capture());
        org.junit.jupiter.api.Assertions.assertEquals("PHYSICAL", saved.getValue().getSubjectType());
    }

    @Test
    void invalidCompanyTypeAndCoordinatesAreRejected() throws Exception {
        save("{\"rma\":\"025680800\",\"name\":\"Новая\",\"subjectType\":\"LEGAL\",\"typeCompany\":3}")
                .andExpect(status().isBadRequest());
        save("{\"rma\":\"025680800\",\"name\":\"Новая\",\"subjectType\":\"LEGAL\",\"latitude\":91}")
                .andExpect(status().isBadRequest());
        save("{\"rma\":\"025680800\",\"name\":\"Новая\",\"subjectType\":\"LEGAL\",\"longitude\":-181}")
                .andExpect(status().isBadRequest());
        verify(repository, never()).save(any());
    }

    @Test
    void negativePayrollAndPlanValuesAreRejected() throws Exception {
        save("{\"rma\":\"025680800\",\"name\":\"Новая\",\"subjectType\":\"LEGAL\",\"percentIncome\":101}")
                .andExpect(status().isBadRequest());
        save("{\"rma\":\"025680800\",\"name\":\"Новая\",\"subjectType\":\"LEGAL\",\"cat1\":-1}")
                .andExpect(status().isBadRequest());
        save("{\"rma\":\"025680800\",\"name\":\"Новая\",\"subjectType\":\"LEGAL\",\"planPassVolume\":-1}")
                .andExpect(status().isBadRequest());
        verify(repository, never()).save(any());
    }

    @Test
    void manualBranchMustPointToAnExistingHeadOrganization() throws Exception {
        var head = legacy("025680801", null);
        when(repository.findByRma("025680801")).thenReturn(Optional.of(head));
        save("{\"rma\":\"025680800\",\"parentRma\":\"025680801\",\"name\":\"Филиал\",\"subjectType\":\"LEGAL\"}")
                .andExpect(status().isCreated());
        save("{\"rma\":\"025680802\",\"parentRma\":\"025680803\",\"name\":\"Филиал\",\"subjectType\":\"LEGAL\"}")
                .andExpect(status().isUnprocessableEntity());
        save("{\"rma\":\"025680804\",\"parentRma\":\"025680804\",\"name\":\"Филиал\",\"subjectType\":\"LEGAL\"}")
                .andExpect(status().isUnprocessableEntity());
    }
}
