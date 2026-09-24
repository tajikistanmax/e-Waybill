package tj.mintrans.epd.masterdata.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tj.mintrans.epd.masterdata.config.CurrentUser;
import tj.mintrans.epd.masterdata.config.SecurityConfig;
import tj.mintrans.epd.masterdata.domain.Employee;
import tj.mintrans.epd.masterdata.domain.Organization;
import tj.mintrans.epd.masterdata.domain.SubjectDocument;
import tj.mintrans.epd.masterdata.repository.DriverRepository;
import tj.mintrans.epd.masterdata.repository.EmployeeRepository;
import tj.mintrans.epd.masterdata.repository.OrganizationRepository;
import tj.mintrans.epd.masterdata.repository.SubjectDocumentRepository;
import tj.mintrans.epd.masterdata.repository.VehicleRepository;
import tj.mintrans.epd.masterdata.service.AuditService;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Изображения для бланка ПЛ (подпись, печать, фото) читает любой, кто вправе печатать лист
 * (инспектор, врач…), — иначе ПЛ печатался бы по-разному в зависимости от того, кто печатает.
 * Прочие виды (паспорт…) — только ведущим документы; тенант — только своя организация.
 */
@WebMvcTest(SubjectDocumentController.class)
@Import(SecurityConfig.class)
class SubjectDocumentLatestAccessTest {

    @Autowired MockMvc mvc;

    @MockitoBean SubjectDocumentRepository documents;
    @MockitoBean VehicleRepository vehicles;
    @MockitoBean DriverRepository drivers;
    @MockitoBean EmployeeRepository employees;
    @MockitoBean OrganizationRepository organizations;
    @MockitoBean CurrentUser currentUser;
    @MockitoBean AuditService audit;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final UUID ORG_ID = UUID.randomUUID();
    private static final String DOCTOR = "111111111";

    @BeforeEach
    void setUp() {
        var e = new Employee();
        e.setRma(DOCTOR);
        e.setOrganizationId(ORG_ID);
        when(employees.findByRma(DOCTOR)).thenReturn(Optional.of(e));
        var doc = new SubjectDocument();
        doc.setContentType("image/png");
        doc.setData(new byte[]{1});
        when(documents.findFirstBySubjectTypeAndSubjectKeyAndDocTypeAndStatusOrderByUploadedAtDesc(
                eq("EMPLOYEE"), eq(DOCTOR), anyString(), eq("APPROVED"))).thenReturn(Optional.of(doc));
    }

    private void asRole(String role) {
        when(currentUser.hasRole(anyString())).thenAnswer(inv -> role.equals(inv.getArgument(0)));
    }

    @Test
    void inspectorReadsSignatureButNotPassport() throws Exception {
        asRole("INSPECTOR");
        when(currentUser.isTenantScoped()).thenReturn(false);
        mvc.perform(get("/api/v1/employees/" + DOCTOR + "/documents/latest?docType=SIGNATURE")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_INSPECTOR"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/employees/" + DOCTOR + "/documents/latest?docType=PASSPORT")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_INSPECTOR"))))
                .andExpect(status().isForbidden());
        // Остальные операции контроллера инспектору по-прежнему закрыты.
        mvc.perform(get("/api/v1/employees/" + DOCTOR + "/documents")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_INSPECTOR"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void tenantSeesOnlyOwnOrganization() throws Exception {
        asRole("MECHANIC");
        when(currentUser.isTenantScoped()).thenReturn(true);
        var own = new Organization();
        org.springframework.test.util.ReflectionTestUtils.setField(own, "id", UUID.randomUUID()); // другая организация
        when(currentUser.organizationRma()).thenReturn(Optional.of("222222222"));
        when(organizations.findByRma("222222222")).thenReturn(Optional.of(own));
        mvc.perform(get("/api/v1/employees/" + DOCTOR + "/documents/latest?docType=SIGNATURE")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MECHANIC"))))
                .andExpect(status().isForbidden());
    }
}
