package tj.mintrans.epd.masterdata.config;

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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Каналы внешней системы-интегратора (сверка 25.09, G2).
 *
 * <p>В «Роҳхат» у каждой учётки {@code company_for_api} свой канал: {@code company.jwt:1} —
 * справочники и путевые листы, {@code company.jwt:2} — только GPS Smart City. У нас внешняя
 * учётка несла ту же роль {@code API_INTEGRATOR}, что и служебная межсервисная, и потому видела
 * всё, что видит служебная: списки водителей и ТС всех организаций, правку справочников.</p>
 *
 * <p>Теперь у внешней учётки в токене claim {@code api_channels}, и фильтр пускает её только в
 * разделы её каналов (остальное — 403). Нет claim'а — ограничения нет: так входит служебная
 * учётка {@code epd-service} и все пользователи платформы.</p>
 */
public class IntegratorChannelFilter extends OncePerRequestFilter {

    /** Разделы этой службы по каналам. Каналов aggregator, gps, neru здесь нет — они в waybill. */
    static final Map<String, List<Pattern>> PATHS = Map.of(
            // Выгрузки справочников, регистрация субъектов и их документы (legacy company.jwt:1).
            "ref", List.of(
                    Pattern.compile("^/api/v1/ref(/.*)?$"),
                    Pattern.compile("^/api/v1/sync(/.*)?$"),
                    Pattern.compile("^/api/v1/(vehicles|drivers|employees)/[^/]+/documents(/.*)?$")));

    /** Все каналы, которые можно выдать учётке. */
    public static final Set<String> CHANNELS = Set.of("ref", "aggregator", "gps", "neru");

    /** Вход и выход — любой учётке. */
    private static final Pattern ALWAYS = Pattern.compile("^/api/v1/auth(/.*)?$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt
                && jwt.getToken().getClaims().get("api_channels") instanceof Collection<?> channels
                && !allowed(channels, request.getRequestURI())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"status\":403,\"detail\":\"Раздел вне каналов этой учётной записи интеграции\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean allowed(Collection<?> channels, String path) {
        if (ALWAYS.matcher(path).matches()) {
            return true;
        }
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
