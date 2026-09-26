package tj.mintrans.epd.masterdata.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сверка 25.09, G2: внешняя учётка интегратора ходит только в разделы своих каналов, служебная
 * (без claim'а {@code api_channels}) — как раньше.
 */
class IntegratorChannelFilterTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static int call(List<String> channels, String path) throws Exception {
        var jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("x");
        if (channels != null) {
            jwt.claim("api_channels", channels);
        }
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt.build(), List.of(new SimpleGrantedAuthority("ROLE_API_INTEGRATOR"))));
        var response = new MockHttpServletResponse();
        new IntegratorChannelFilter().doFilter(new MockHttpServletRequest("GET", path), response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    @DisplayName("канал ref: выгрузки, регистрация субъектов и их документы — да; водители всех организаций — нет")
    void refChannel() throws Exception {
        assertThat(call(List.of("ref"), "/api/v1/ref/drivers")).isEqualTo(200);
        assertThat(call(List.of("ref"), "/api/v1/sync/driver")).isEqualTo(200);
        assertThat(call(List.of("ref"), "/api/v1/drivers/123/documents")).isEqualTo(200);
        assertThat(call(List.of("ref"), "/api/v1/drivers")).isEqualTo(403);
        assertThat(call(List.of("ref"), "/api/v1/organizations")).isEqualTo(403);
    }

    @Test
    @DisplayName("GPS Smart City (legacy company.jwt:2) в справочники не ходит; вход — любой учётке")
    void gpsChannel() throws Exception {
        assertThat(call(List.of("gps"), "/api/v1/ref/companies")).isEqualTo(403);
        assertThat(call(List.of("gps"), "/api/v1/auth/logout")).isEqualTo(200);
    }

    @Test
    @DisplayName("служебная учётка без claim'а — без ограничения")
    void serviceAccountUnrestricted() throws Exception {
        assertThat(call(null, "/api/v1/drivers")).isEqualTo(200);
    }
}
