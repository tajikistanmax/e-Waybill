package tj.mintrans.epd.masterdata.web;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import tj.mintrans.epd.masterdata.config.SecurityConfig;
import tj.mintrans.epd.masterdata.repository.BrandRepository;
import tj.mintrans.epd.masterdata.repository.CityCoefRepository;
import tj.mintrans.epd.masterdata.repository.DirectionRepository;
import tj.mintrans.epd.masterdata.repository.DriveClassRepository;
import tj.mintrans.epd.masterdata.repository.FuelWinterCoefRepository;
import tj.mintrans.epd.masterdata.repository.MountainCoefRepository;
import tj.mintrans.epd.masterdata.repository.RouteTariffRepository;
import tj.mintrans.epd.masterdata.repository.UsedCoefRepository;
import tj.mintrans.epd.masterdata.service.AuditService;

import java.util.stream.Stream;

import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Права на запись справочников расчётного ядра ({@link LegacyReferenceController}).
 *
 * <p>Раньше контроллер был read-only (см. историю Javadoc класса): правка требовала ручной
 * SQL-миграции. Теперь POST есть, но эти справочники единые для платформы (не организация-скоуп,
 * питают движок расчёта у ВСЕХ перевозчиков) — писать вправе только SYSTEM_ADMIN, как нормы/
 * коэффициенты/тарифы в {@link DictionaryController}. GET по-прежнему открыт любому
 * аутентифицированному (поведение не менялось).</p>
 */
@WebMvcTest(LegacyReferenceController.class)
@Import(SecurityConfig.class)
class LegacyReferenceControllerSecurityTest {

    @Autowired MockMvc mvc;

    @MockitoBean BrandRepository brands;
    @MockitoBean tj.mintrans.epd.masterdata.repository.VehicleRepository vehicles;
    @MockitoBean FuelWinterCoefRepository winterCoefs;
    @MockitoBean MountainCoefRepository mountainCoefs;
    @MockitoBean CityCoefRepository cityCoefs;
    @MockitoBean UsedCoefRepository usedCoefs;
    @MockitoBean DriveClassRepository driveClasses;
    @MockitoBean DirectionRepository directions;
    @MockitoBean RouteTariffRepository routeTariffs;
    @MockitoBean AuditService audit;
    @MockitoBean JwtDecoder jwtDecoder;

    private static RequestPostProcessor as(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static final String ROUTE_ID = "11111111-1111-1111-1111-111111111111";

    /** Валидные тела запроса для каждого POST — иначе 400 (валидация) маскировал бы 403 (доступ). */
    static Stream<Arguments> endpoints() {
        return Stream.of(
                Arguments.of("/api/v1/legacy-ref/brands", "{\"name\":\"Тестовая марка\"}"),
                Arguments.of("/api/v1/legacy-ref/winter-coefs", "{\"name\":\"Тестовый зимний\",\"coef\":5}"),
                Arguments.of("/api/v1/legacy-ref/mountain-coefs", "{\"name\":\"Тестовый горный\",\"coef\":5}"),
                Arguments.of("/api/v1/legacy-ref/city-coefs", "{\"name\":\"Тестовый городской\",\"coef\":5}"),
                Arguments.of("/api/v1/legacy-ref/used-coefs", "{\"year\":5,\"km\":100000,\"coef\":5}"),
                Arguments.of("/api/v1/legacy-ref/drive-classes", "{\"driveClass\":\"1\",\"coef\":10}"),
                Arguments.of("/api/v1/legacy-ref/directions", "{\"title\":\"Тестовое направление\"}"),
                Arguments.of("/api/v1/legacy-ref/route-tariffs",
                        "{\"routeId\":\"" + ROUTE_ID + "\",\"pricePer1Mkm\":1.5,\"priceOneTime\":2.5}")
        );
    }

    @ParameterizedTest
    @MethodSource("endpoints")
    void postAllowedForSystemAdmin(String path, String body) throws Exception {
        mvc.perform(post(path).with(as("SYSTEM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(not(403)));
    }

    @ParameterizedTest
    @MethodSource("endpoints")
    void postForbiddenForCompanyAdmin(String path, String body) throws Exception {
        // COMPANY_ADMIN правит собственные маршруты/клиентов, но НЕ платформенные справочники —
        // тот же принцип, что и /api/v1/dictionaries/{fuel-norms,coefficients,tariffs}.
        mvc.perform(post(path).with(as("COMPANY_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @MethodSource("endpoints")
    void postUnauthorizedAnonymous(String path, String body) throws Exception {
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"brands", "winter-coefs", "mountain-coefs", "city-coefs", "used-coefs",
            "drive-classes", "directions", "route-tariffs"})
    void getOpenToAnyAuthenticatedRole(String segment) throws Exception {
        // Поведение чтения не менялось этой правкой — любая аутентифицированная роль видит справочник.
        mvc.perform(get("/api/v1/legacy-ref/" + segment).with(as("DISPATCHER")))
                .andExpect(status().is(not(403)));
    }
}
