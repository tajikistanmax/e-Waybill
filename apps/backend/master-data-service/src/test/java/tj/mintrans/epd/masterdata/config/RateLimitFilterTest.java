package tj.mintrans.epd.masterdata.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ограничитель частоты: счёт по пользователю (основной предел) и по адресу (внешняя граница).
 *
 * <p>До 22.09.2026 счёт шёл только по адресу, поэтому предприятие за одним внешним адресом
 * делило 300 запросов в минуту между всеми сотрудниками — примерно 30 открытий страницы в
 * минуту на всю организацию (находка сквозной приёмки, блок A5).</p>
 */
class RateLimitFilterTest {

    /** Токен с заданным субъектом: подпись не важна, фильтр её не проверяет. */
    private static String tokenFor(String subject) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"sub\":\"" + subject + "\",\"iss\":\"epd\"}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".signature";
    }

    private static HttpServletRequest request(String ip, String subject) {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getRequestURI()).thenReturn("/api/v1/vehicles");
        when(r.getRemoteAddr()).thenReturn(ip);
        when(r.getHeader("Authorization")).thenReturn(subject == null ? null : "Bearer " + tokenFor(subject));
        return r;
    }

    private static HttpServletResponse response() throws Exception {
        HttpServletResponse resp = mock(HttpServletResponse.class);
        when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        return resp;
    }

    @Test
    @DisplayName("субъект токена становится ключом корзины")
    void subjectIsTakenFromToken() {
        var r = request("10.0.0.1", "user-42");

        assertThat(RateLimitFilter.subjectKey(r)).isEqualTo("sub:user-42");
    }

    @Test
    @DisplayName("без токена ключа пользователя нет — остаётся только предел по адресу")
    void noTokenNoUserKey() {
        assertThat(RateLimitFilter.subjectKey(request("10.0.0.1", null))).isNull();
    }

    @Test
    @DisplayName("непарсимый токен даёт отпечаток вместо субъекта")
    void brokenTokenFallsBackToFingerprint() {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getHeader("Authorization")).thenReturn("Bearer not-a-jwt");

        assertThat(RateLimitFilter.subjectKey(r)).startsWith("tok:");
    }

    @Test
    @DisplayName("два сотрудника за одним адресом не делят лимит друг друга")
    void twoUsersBehindOneAddressDoNotShareLimit() throws Exception {
        // предел пользователя 3, предел адреса 100 — упереться можно только в свой
        var filter = new RateLimitFilter(true, 60, 3, 60, 100, 1000);
        FilterChain chain = mock(FilterChain.class);

        // Алиса выбирает свой предел до конца
        for (int i = 0; i < 3; i++) {
            filter.doFilterInternal(request("10.0.0.1", "alice"), response(), chain);
        }
        verify(chain, times(3)).doFilter(any(), any());

        // её следующий запрос отклонён
        HttpServletResponse denied = response();
        filter.doFilterInternal(request("10.0.0.1", "alice"), denied, chain);
        verify(denied).setStatus(429);
        verify(chain, times(3)).doFilter(any(), any());

        // Боб с того же адреса работает со своим пределом — раньше он был бы уже исчерпан
        for (int i = 0; i < 3; i++) {
            filter.doFilterInternal(request("10.0.0.1", "bob"), response(), chain);
        }
        verify(chain, times(6)).doFilter(any(), any());
    }

    @Test
    @DisplayName("предел по адресу остаётся внешней границей для потока с разными токенами")
    void addressLimitStillBoundsForgedTokens() throws Exception {
        var filter = new RateLimitFilter(true, 60, 1000, 60, 5, 1000);
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 5; i++) {
            filter.doFilterInternal(request("10.0.0.9", "forged-" + i), response(), chain);
        }
        verify(chain, times(5)).doFilter(any(), any());

        HttpServletResponse denied = response();
        filter.doFilterInternal(request("10.0.0.9", "forged-999"), denied, chain);
        verify(denied).setStatus(429);
    }

    @Test
    @DisplayName("служебная учётка (API_INTEGRATOR) — свой, более высокий предел; человек — прежний")
    void integratorHasOwnHigherLimit() throws Exception {
        // человек 3, служебная учётка 6, адрес 100
        var filter = new RateLimitFilter(true, 60, 3, 60, 100, 1000, 6);
        FilterChain chain = mock(FilterChain.class);
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"aggregator\",\"realm_access\":{\"roles\":[\"API_INTEGRATOR\"]}}".getBytes(StandardCharsets.UTF_8));
        HttpServletRequest agg = mock(HttpServletRequest.class);
        when(agg.getRequestURI()).thenReturn("/api/v1/vehicles");
        when(agg.getRemoteAddr()).thenReturn("10.0.0.7");
        when(agg.getHeader("Authorization")).thenReturn("Bearer " + header + "." + payload + ".sig");

        assertThat(RateLimitFilter.isIntegrator(agg)).isTrue();
        assertThat(RateLimitFilter.isIntegrator(request("10.0.0.7", "dispatcher"))).isFalse();
        for (int i = 0; i < 6; i++) {
            filter.doFilterInternal(agg, response(), chain);
        }
        verify(chain, times(6)).doFilter(any(), any());
        HttpServletResponse denied = response();
        filter.doFilterInternal(agg, denied, chain);
        verify(denied).setStatus(429);
    }

    @Test
    @DisplayName("выключенный ограничитель пропускает всё")
    void disabledPassesEverything() throws Exception {
        var filter = new RateLimitFilter(false, 60, 1, 1, 1, 10);
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 10; i++) {
            filter.doFilterInternal(request("10.0.0.2", "carol"), response(), chain);
        }
        verify(chain, times(10)).doFilter(any(), any());
    }

    @Test
    @DisplayName("публичный путь считается только по адресу, со своим строгим потолком")
    void publicPathUsesOwnAddressBucket() throws Exception {
        var filter = new RateLimitFilter(true, 60, 1000, 2, 1000, 1000);
        FilterChain chain = mock(FilterChain.class);
        HttpServletRequest pub = mock(HttpServletRequest.class);
        when(pub.getRequestURI()).thenReturn("/api/v1/settings/public");
        when(pub.getRemoteAddr()).thenReturn("10.0.0.3");

        filter.doFilterInternal(pub, response(), chain);
        filter.doFilterInternal(pub, response(), chain);
        verify(chain, times(2)).doFilter(any(), any());

        HttpServletResponse denied = response();
        filter.doFilterInternal(pub, denied, chain);
        verify(denied).setStatus(429);
        verify(pub, never()).getHeader("Authorization");
    }
}
