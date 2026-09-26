package tj.mintrans.epd.waybill.config;

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

/** Сверка 25.09, G2: каналы внешней учётки интегратора в службе путевых листов. */
class IntegratorChannelFilterTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static int call(List<String> channels, String method, String path) throws Exception {
        var jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("x");
        if (channels != null) {
            jwt.claim("api_channels", channels);
        }
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt.build(), List.of(new SimpleGrantedAuthority("ROLE_API_INTEGRATOR"))));
        var response = new MockHttpServletResponse();
        new IntegratorChannelFilter().doFilter(new MockHttpServletRequest(method, path), response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    @DisplayName("Smart City (gps) шлёт координаты, но листов не видит; КВД (ref) — наоборот")
    void channelsSeparateSystems() throws Exception {
        assertThat(call(List.of("gps"), "POST", "/api/v1/gps/events")).isEqualTo(200);
        assertThat(call(List.of("gps"), "GET", "/api/v1/ref/waybills")).isEqualTo(403);
        assertThat(call(List.of("ref"), "POST", "/api/v1/ref/waybill3c")).isEqualTo(200);
        assertThat(call(List.of("ref"), "POST", "/api/v1/gps/events")).isEqualTo(403);
        assertThat(call(List.of("ref", "gps"), "GET", "/api/v1/waybills")).isEqualTo(403);
        assertThat(call(List.of("aggregator"), "POST", "/api/v1/aggregator/waybills")).isEqualTo(200);
        assertThat(call(List.of("neru"), "GET", "/api/v1/neru/active-by-plate")).isEqualTo(200);
    }

    @Test
    @DisplayName("служебная учётка без claim'а — без ограничения")
    void serviceAccountUnrestricted() throws Exception {
        assertThat(call(null, "GET", "/api/v1/waybills")).isEqualTo(200);
        assertThat(IntegratorChannelFilter.currentChannels()).isEmpty();
    }
}
