package tj.mintrans.epd.waybill.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Каналы внешней системы-интегратора (сверка 25.09, G2) — пара к одноимённому фильтру
 * master-data. У внешней учётки в токене claim {@code api_channels}; она ходит только в разделы
 * своих каналов (остальное — 403). Нет claim'а — ограничения нет (служебная учётка, люди).
 *
 * <p>В «Роҳхат»: {@code company.jwt:1} — справочники и путевые листы ({@code ref}),
 * {@code company.jwt:2} — только GPS Smart City ({@code gps}).</p>
 */
public class IntegratorChannelFilter extends OncePerRequestFilter {

    static final Map<String, List<Pattern>> PATHS = Map.of(
            "ref", List.of(Pattern.compile("^/api/v1/ref(/.*)?$")),
            "aggregator", List.of(Pattern.compile("^/api/v1/aggregator(/.*)?$")),
            "gps", List.of(Pattern.compile("^/api/v1/gps(/.*)?$")),
            "neru", List.of(Pattern.compile("^/api/v1/neru(/.*)?$")));

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var channels = currentChannels();
        if (channels.isPresent() && !allowed(channels.get(), request.getRequestURI())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"status\":403,\"detail\":\"Раздел вне каналов этой учётной записи интеграции\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Каналы вызывающего, если это внешняя учётка интегратора. */
    public static Optional<Collection<?>> currentChannels() {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt
                && jwt.getToken().getClaims().get("api_channels") instanceof Collection<?> channels) {
            return Optional.of(channels);
        }
        return Optional.empty();
    }

    static boolean allowed(Collection<?> channels, String path) {
        for (Object c : channels) {
            for (Pattern p : PATHS.getOrDefault(String.valueOf(c), List.of())) {
                if (p.matcher(path).matches()) {
                    return true;
                }
            }
        }
        return false;
    }
}
