package tj.mintrans.epd.masterdata.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tj.mintrans.epd.masterdata.auth.UserDirectory;
import tj.mintrans.epd.masterdata.config.CurrentUser;
import tj.mintrans.epd.masterdata.config.SecurityConfig;
import tj.mintrans.epd.masterdata.domain.AppUser;
import tj.mintrans.epd.masterdata.domain.Organization;
import tj.mintrans.epd.masterdata.repository.AppUserRepository;
import tj.mintrans.epd.masterdata.repository.OrganizationRepository;
import tj.mintrans.epd.masterdata.service.AuditService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * «Пользователи» администратора платформы (замена legacy /admin/user): только SYSTEM_ADMIN,
 * платформенные роли без организации, роли перевозчика — только с существующей организацией,
 * себе и служебным учёткам роль не меняется.
 */
@WebMvcTest(PlatformUserController.class)
@Import(SecurityConfig.class)
class PlatformUserControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean AppUserRepository users;
    @MockitoBean UserDirectory directory;
    @MockitoBean OrganizationRepository organizations;
    @MockitoBean CurrentUser currentUser;
    @MockitoBean AuditService audit;
    @MockitoBean tj.mintrans.epd.masterdata.auth.IntegratorAccounts integrators;
    @MockitoBean JwtDecoder jwtDecoder;

    private static final UUID NEW_ID = UUID.randomUUID();

    private static RequestPostProcessor as(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static AppUser user(UUID id, String username, String roles) {
        var u = new AppUser();
        u.setId(id);
        u.setUsername(username);
        u.setPasswordHash("x");
        u.setRoleList(List.of(roles.split(",")));
        return u;
    }

    @BeforeEach
    void setUp() {
        when(currentUser.subject()).thenReturn(Optional.of("me"));
        when(directory.createUser(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(NEW_ID.toString());
        when(users.findById(NEW_ID)).thenReturn(Optional.of(user(NEW_ID, "new-user", "MINTRANS_ANALYST")));
        when(users.search(anyString(), anyString(), anyString(), anyBoolean(), anyString(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(user(UUID.randomUUID(), "analyst", "MINTRANS_ANALYST"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPANY_ADMIN", "BRANCH_ADMIN", "MINTRANS_ANALYST", "INSPECTOR", "DISPATCHER"})
    void onlyPlatformAdmin(String role) throws Exception {
        mvc.perform(get("/api/v1/platform-users").with(as(role))).andExpect(status().isForbidden());
    }

    @Test
    void listSearchesWholeWordRole() throws Exception {
        mvc.perform(get("/api/v1/platform-users?role=admin&q=Ali").with(as("SYSTEM_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].username").value("analyst"));
        verify(users).search(eq("%ali%"), eq("%,ADMIN,%"), eq(""), eq(false), eq(""), any(), any());
    }

    @Test
    void platformRoleWithoutOrganizationGetsSecondFactor() throws Exception {
        mvc.perform(post("/api/v1/platform-users").with(as("SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"new-analyst\",\"role\":\"MINTRANS_ANALYST\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.temporaryPassword").isNotEmpty());
        verify(directory).setSecondFactorRequired(NEW_ID.toString(), true);
    }

    @Test
    void carrierRoleNeedsExistingOrganization() throws Exception {
        mvc.perform(post("/api/v1/platform-users").with(as("SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"disp\",\"role\":\"DISPATCHER\"}"))
                .andExpect(status().isUnprocessableEntity());
        when(organizations.findByRma("999999999")).thenReturn(Optional.empty());
        mvc.perform(post("/api/v1/platform-users").with(as("SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"disp\",\"role\":\"DISPATCHER\",\"organizationRma\":\"999999999\"}"))
                .andExpect(status().isUnprocessableEntity());
        when(organizations.findByRma("025680800")).thenReturn(Optional.of(new Organization()));
        mvc.perform(post("/api/v1/platform-users").with(as("SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"disp\",\"role\":\"DISPATCHER\",\"organizationRma\":\"025680800\"}"))
                .andExpect(status().isCreated());
        verify(directory, never()).setSecondFactorRequired(any(), eq(true));
    }

    /** Сверка 25.09, G2: внешняя система — только с каналами, и только из известных. */
    @Test
    void integratorNeedsKnownChannels() throws Exception {
        mvc.perform(post("/api/v1/platform-users").with(as("SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"svc\",\"role\":\"API_INTEGRATOR\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/platform-users").with(as("SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"svc\",\"role\":\"API_INTEGRATOR\",\"apiChannels\":[\"admin\"]}"))
                .andExpect(status().isUnprocessableEntity());
        verify(directory, never()).createUser(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void integratorIsCreatedWithChannelsAndPermanentPassword() throws Exception {
        mvc.perform(post("/api/v1/platform-users").with(as("SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"smart-city\",\"role\":\"api_integrator\",\"apiChannels\":[\"GPS\",\"ref\",\"gps\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.temporaryPassword").isNotEmpty());
        verify(directory).configureIntegrator(NEW_ID.toString(), List.of("gps", "ref"));
        verify(directory, never()).setSecondFactorRequired(any(), eq(true));
    }

    @Test
    void channelsOfEnvironmentAccountAreNotEditable() throws Exception {
        UUID agg = UUID.randomUUID();
        var account = user(agg, "epd-aggregator", "API_INTEGRATOR");
        when(users.findById(agg)).thenReturn(Optional.of(account));
        when(integrators.environmentManaged(account)).thenReturn(true);
        mvc.perform(patch("/api/v1/platform-users/" + agg + "/channels").with(as("SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"apiChannels\":[\"gps\"]}"))
                .andExpect(status().isUnprocessableEntity());
        verify(directory, never()).configureIntegrator(any(), any());
    }

    @Test
    void cannotChangeOwnOrServiceRole() throws Exception {
        UUID me = UUID.randomUUID();
        when(currentUser.subject()).thenReturn(Optional.of(me.toString()));
        when(users.findById(me)).thenReturn(Optional.of(user(me, "admin", "SYSTEM_ADMIN")));
        mvc.perform(patch("/api/v1/platform-users/" + me + "/role").with(as("SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"INSPECTOR\"}"))
                .andExpect(status().isUnprocessableEntity());

        UUID svc = UUID.randomUUID();
        when(users.findById(svc)).thenReturn(Optional.of(user(svc, "epd-service", "API_INTEGRATOR")));
        mvc.perform(patch("/api/v1/platform-users/" + svc + "/role").with(as("SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"INSPECTOR\"}"))
                .andExpect(status().isUnprocessableEntity());
        verify(directory, never()).setSingleRealmRole(any(), any(), any());
    }
}
