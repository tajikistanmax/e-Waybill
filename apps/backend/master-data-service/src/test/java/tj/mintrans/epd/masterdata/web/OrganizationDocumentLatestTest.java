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
import tj.mintrans.epd.masterdata.domain.Organization;
import tj.mintrans.epd.masterdata.domain.OrganizationDocument;
import tj.mintrans.epd.masterdata.repository.OrganizationDocumentRepository;
import tj.mintrans.epd.masterdata.repository.OrganizationRepository;
import tj.mintrans.epd.masterdata.service.AuditService;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Печать организации на бланке ПЛ (legacy companies.seal_attach): отдаётся только последнее
 * ОДОБРЕННОЕ изображение вида SEAL и только своей организации для тенанта.
 */
@WebMvcTest(OrganizationDocumentController.class)
@Import(SecurityConfig.class)
class OrganizationDocumentLatestTest {

    @Autowired MockMvc mvc;

    @MockitoBean OrganizationDocumentRepository documents;
    @MockitoBean OrganizationRepository organizations;
    @MockitoBean CurrentUser currentUser;
    @MockitoBean AuditService audit;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final String OWN = "025680800";
    private static final String OTHER = "111111111";

    private static OrganizationDocument doc(String contentType, byte[] data) {
        var d = new OrganizationDocument();
        d.setOrganizationRma(OWN);
        d.setDocType("SEAL");
        d.setContentType(contentType);
        d.setData(data);
        d.setStatus("APPROVED");
        return d;
    }

    @BeforeEach
    void setUp() {
        when(organizations.findByRma(anyString())).thenReturn(Optional.of(new Organization()));
        when(currentUser.isTenantScoped()).thenReturn(true);
        when(currentUser.organizationRma()).thenReturn(Optional.of(OWN));
    }

    @Test
    void approvedSealImageOfOwnOrganization() throws Exception {
        when(documents.findFirstByOrganizationRmaAndDocTypeAndStatusOrderByUploadedAtDesc(OWN, "SEAL", "APPROVED"))
                .thenReturn(Optional.of(doc("image/png", new byte[]{1, 2, 3})));
        mvc.perform(get("/api/v1/organizations/" + OWN + "/documents/latest?docType=SEAL")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }

    @Test
    void noApprovedOrNotImageIsNotFound() throws Exception {
        when(documents.findFirstByOrganizationRmaAndDocTypeAndStatusOrderByUploadedAtDesc(OWN, "SEAL", "APPROVED"))
                .thenReturn(Optional.of(doc("application/pdf", new byte[]{1})));
        mvc.perform(get("/api/v1/organizations/" + OWN + "/documents/latest?docType=SEAL")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isNotFound());
        when(documents.findFirstByOrganizationRmaAndDocTypeAndStatusOrderByUploadedAtDesc(OWN, "SEAL", "APPROVED"))
                .thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/organizations/" + OWN + "/documents/latest?docType=SEAL")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void otherOrganizationIsHiddenFromTenant() throws Exception {
        when(documents.findFirstByOrganizationRmaAndDocTypeAndStatusOrderByUploadedAtDesc(OTHER, "SEAL", "APPROVED"))
                .thenReturn(Optional.of(doc("image/png", new byte[]{9})));
        mvc.perform(get("/api/v1/organizations/" + OTHER + "/documents/latest?docType=SEAL")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
                .andExpect(status().isNotFound());
    }
}
