package tj.mintrans.epd.waybill.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Агрегатор (роль API_INTEGRATOR) грузит путевые листы пачками одной учёткой — у него свой,
 * более высокий предел частоты; у людей предел прежний (нагрузочный тест 24.09.2026).
 */
class RateLimitFilterIntegratorTest {

    private static HttpServletRequest request(String payloadJson) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getRequestURI()).thenReturn("/api/v1/aggregator/waybills");
        when(r.getRemoteAddr()).thenReturn("10.0.0.8");
        when(r.getHeader("Authorization")).thenReturn("Bearer " + header + "." + payload + ".sig");
        return r;
    }

    private static HttpServletResponse response() throws Exception {
        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        return resp;
    }

    @Test
    void integratorGetsOwnLimitPeopleKeepTheirs() throws Exception {
        var filter = new RateLimitFilter(true, 60, 2, 60, 100, 1000, 5);
        FilterChain chain = mock(FilterChain.class);
        var aggregator = request("{\"sub\":\"agg\",\"realm_access\":{\"roles\":[\"API_INTEGRATOR\"]}}");
        var person = request("{\"sub\":\"disp\",\"realm_access\":{\"roles\":[\"DISPATCHER\"]}}");

        assertThat(RateLimitFilter.isIntegrator(aggregator)).isTrue();
        assertThat(RateLimitFilter.isIntegrator(person)).isFalse();

        for (int i = 0; i < 5; i++) {
            filter.doFilterInternal(aggregator, response(), chain);
        }
        verify(chain, times(5)).doFilter(any(), any());

        filter.doFilterInternal(person, response(), chain);
        filter.doFilterInternal(person, response(), chain);
        HttpServletResponse denied = response();
        filter.doFilterInternal(person, denied, chain);
        verify(denied).setStatus(429);
        verify(chain, times(7)).doFilter(any(), any());
    }
}
