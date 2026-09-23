package tj.mintrans.epd.masterdata.web;

import org.junit.jupiter.api.BeforeEach;
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
import tj.mintrans.epd.masterdata.auth.UserDirectory;
import tj.mintrans.epd.masterdata.config.CurrentUser;
import tj.mintrans.epd.masterdata.config.SecurityConfig;
import tj.mintrans.epd.masterdata.config.TenantScope;
import tj.mintrans.epd.masterdata.service.AuditService;

import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Права на провижининг логинов сотрудников ({@link OrgUserController}).
 *
 * <p>Выдавать вход в модуль вправе только администратор компании, филиала и платформы.
 * Диспетчер, аналитик и операционные роли — нет (иначе перевозчик мог бы плодить доступы
 * в обход контроля). Белый список ролей проверяется отдельно на уровне сервиса.</p>
 */
@WebMvcTest(OrgUserController.class)
@Import(SecurityConfig.class)
class OrgUserControllerSecurityTest {

    @Autowired MockMvc mvc;

    @MockitoBean UserDirectory keycloak;
    @MockitoBean CurrentUser currentUser;
    @MockitoBean TenantScope tenantScope;
    @MockitoBean AuditService audit;
    @MockitoBean JwtDecoder jwtDecoder;

    private static RequestPostProcessor as(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static final String CREATE = """
            {"username":"+992900000000","organizationRma":"025680800","role":"DRIVER"}""";

    /**
     * Разрешённый путь не должен падать в NPE на замоканном справочнике учётных записей —
     * иначе тест проверял бы обработку ошибки, а не право доступа. Задаём валидные ответы.
     */
    @BeforeEach
    void stubKeycloak() {
        var sample = new UserDirectory.OrgUser("uid", "+992900000000", "Иван", "Иванов",
                true, "111111111", "025680800", java.util.List.of("DRIVER"));
        org.mockito.Mockito.lenient()
                .when(keycloak.createUser(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn("uid");
        org.mockito.Mockito.lenient()
                .when(keycloak.getUser(org.mockito.ArgumentMatchers.any())).thenReturn(sample);
        org.mockito.Mockito.lenient()
                .when(keycloak.listByOrganizations(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of(sample));
    }

    // --- Список доступов и выдача — администраторы перевозчика и платформы.

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "COMPANY_ADMIN", "BRANCH_ADMIN"})
    void listAllowed(String role) throws Exception {
        mvc.perform(get("/api/v1/org-users").with(as(role)))
                .andExpect(status().is(not(403)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DISPATCHER", "MINTRANS_ANALYST", "DOCTOR", "ACCOUNTANT", "DRIVER", "INSPECTOR"})
    void listForbidden(String role) throws Exception {
        mvc.perform(get("/api/v1/org-users").with(as(role)))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SYSTEM_ADMIN", "COMPANY_ADMIN", "BRANCH_ADMIN"})
    void createAllowed(String role) throws Exception {
        mvc.perform(post("/api/v1/org-users").with(as(role))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE))
                .andExpect(status().is(not(403)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DISPATCHER", "MINTRANS_ANALYST", "ACCOUNTANT", "DRIVER"})
    void createForbidden(String role) throws Exception {
        mvc.perform(post("/api/v1/org-users").with(as(role))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/org-users")).andExpect(status().isUnauthorized());
    }

    // --- Управление чужой учёткой: только в пределах своего уровня (живая находка 23.09.2026:
    // администратор компании мог сбросить пароль инспектору, заведённому в его организацию).

    private void asCompanyAdminOver(String targetRole) {
        org.mockito.Mockito.when(tenantScope.isBounded()).thenReturn(true);
        org.mockito.Mockito.when(tenantScope.contains("025680800")).thenReturn(true);
        org.mockito.Mockito.when(currentUser.hasRole("COMPANY_ADMIN")).thenReturn(true);
        org.mockito.Mockito.when(currentUser.subject()).thenReturn(java.util.Optional.of("company-admin-id"));
        org.mockito.Mockito.when(keycloak.getUser("target")).thenReturn(new UserDirectory.OrgUser(
                "target", "992900000009", "Т", "Т", true, null, "025680800", java.util.List.of(targetRole)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSPECTOR", "COMPANY_ADMIN", "SYSTEM_ADMIN", "CUSTOMS_OFFICER"})
    void companyAdminCannotTakeOverHigherAccounts(String targetRole) throws Exception {
        asCompanyAdminOver(targetRole);
        mvc.perform(post("/api/v1/org-users/target/reset-password").with(as("COMPANY_ADMIN")))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DISPATCHER", "DRIVER", "BRANCH_ADMIN", "CLIENT_SENDER"})
    void companyAdminManagesOwnStaff(String targetRole) throws Exception {
        asCompanyAdminOver(targetRole);
        mvc.perform(post("/api/v1/org-users/target/reset-password").with(as("COMPANY_ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void cannotDisableOwnAccount() throws Exception {
        asCompanyAdminOver("DISPATCHER");
        org.mockito.Mockito.when(currentUser.subject()).thenReturn(java.util.Optional.of("target"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/org-users/target/enabled").with(as("COMPANY_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isUnprocessableEntity());
    }
}
