package tj.mintrans.epd.waybill.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Клиент мастер-данных (единая платформа Минтранса; на этапе 1а — master-data-service).
 * Возвращает «сырые» Map — они же становятся снимками (snapshot) в документе.
 * Bearer-токен текущего запроса пробрасывается дальше (master-data требует JWT).
 */
@Component
public class MasterDataClient {

    private final RestClient client;
    private final ServiceTokenProvider serviceToken;

    public MasterDataClient(@Value("${epd.master-data.base-url}") String baseUrl,
                            ServiceTokenProvider serviceToken) {
        this.serviceToken = serviceToken;
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor(this::authorize)
                .build();
    }

    /**
     * Аутентификация вызова master-data: пробрасывается Bearer текущего пользователя (token relay);
     * если запрос без токена (агрегатор ЧУРА/НЕРУ, планировщик) — берётся сервисный client-credentials
     * токен (роль API_INTEGRATOR), чтобы master-data не приходилось держать GET открытым анонимно.
     */
    private ClientHttpResponse authorize(HttpRequest request, byte[] body,
                                         ClientHttpRequestExecution execution) throws IOException {
        if (!request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
            String userAuthorization = null;
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
                userAuthorization = attrs.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
            }
            String authorization = userAuthorization != null
                    ? userAuthorization
                    : "Bearer " + serviceToken.bearer();
            request.getHeaders().set(HttpHeaders.AUTHORIZATION, authorization);
        }
        return execution.execute(request, body);
    }

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_OF_MAPS =
            new ParameterizedTypeReference<>() {};

    public Optional<Map<String, Object>> findOrganization(String rma) {
        return first("/api/v1/organizations?rma={rma}", rma);
    }

    public Optional<Map<String, Object>> findDriver(String rma) {
        return first("/api/v1/drivers?rma={rma}", rma);
    }

    public Optional<Map<String, Object>> findVehicle(String registrationNumber) {
        return first("/api/v1/vehicles?registrationNumber={n}", registrationNumber);
    }

    public Optional<Map<String, Object>> findEmployee(String rma) {
        return first("/api/v1/employees?rma={rma}", rma);
    }

    /** Все ТС организации (для норматива выдачи листов count_waybills_type2). */
    public List<Map<String, Object>> listVehicles(String organizationRma) {
        return list("/api/v1/vehicles?organizationRma={o}", organizationRma);
    }

    public List<Map<String, Object>> listOrganizations() {
        return list("/api/v1/organizations");
    }

    /**
     * РМА организаций, видимых текущему пользователю (token relay → master-data сам
     * применяет тенант-скоуп: своя организация + её филиалы для администратора компании).
     * Ошибка/недоступность master-data → пустой список (вызывающий откатывается на claim).
     */
    public List<String> scopedOrganizationRmas() {
        try {
            return listOrganizations().stream()
                    .map(o -> o.get("rma"))
                    .filter(java.util.Objects::nonNull)
                    .map(String::valueOf)
                    .filter(s -> !s.isBlank())
                    .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** Онлайн-проверка дозвола E-PERMIT через единую платформу (404 → empty). */
    public Optional<Map<String, Object>> findPermit(String permitNumber) {
        try {
            Map<String, Object> permit = client.get()
                    .uri("/api/v1/sync/permit/{number}", permitNumber)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            return Optional.ofNullable(permit);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    /**
     * Фиксирует в аудите master-data-service факт доступа к расшифрованным медицинским
     * показателям (ИБ-13.1.3). Токен текущего пользователя ретранслируется (см. authorize())
     * — актор в аудите master-data будет реальным инициатором, не сервисной учёткой.
     * Намеренно БЕЗ try/catch: сбой аудита должен блокировать выдачу показателей
     * (fail-closed) — иначе «доступ только с фиксацией в аудите» не гарантия, а пожелание.
     */
    public void recordMedicalAccess(String waybillId, String titleType) {
        client.post()
                .uri("/api/v1/audit/medical-access")
                .body(Map.of("waybillId", waybillId, "titleType", titleType))
                .retrieve()
                .toBodilessEntity();
    }

    public void updateVehicleOdometer(String vehicleId, int odometer) {
        // Перенос пробега при закрытии ПЛ — СИСТЕМНАЯ операция: форсируем сервисный токен
        // (API_INTEGRATOR), а не пользовательский, т.к. эндпоинт закрыт ролью сервиса и не
        // должен зависеть от роли инициатора закрытия (диспетчер/бухгалтер).
        client.patch()
                .uri("/api/v1/vehicles/{id}/odometer", vehicleId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken.bearer())
                .body(Map.of("odometer", odometer))
                .retrieve()
                .toBodilessEntity();
    }

    // ------------------------------------------------------- движок политик

    private static final ParameterizedTypeReference<Map<String, String>> STRING_MAP =
            new ParameterizedTypeReference<>() {};

    /**
     * Эффективные правила (движок политик master-data) для (организация, тип ПЛ).
     * При недоступности движка возвращает пустую карту — вызывающий код применяет безопасный фолбэк.
     */
    public Map<String, String> effectivePolicies(String organizationRma, String waybillType) {
        try {
            Map<String, String> map = client.get()
                    .uri("/api/v1/policies/effective?organizationRma={o}&waybillType={t}",
                            organizationRma, waybillType)
                    .retrieve()
                    .body(STRING_MAP);
            return map == null ? Map.of() : map;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /**
     * Тумблеры уведомлений (настройки master-data, категория notifications): ключ→значение.
     * При недоступности — пустая карта (вызывающий трактует отсутствие как «уведомление включено»).
     */
    public Map<String, String> notificationSettings() {
        return settingsByCategory("notifications");
    }

    /**
     * Настройки печати (master-data, категория {@code print}): опции бланка ПЛ и реквизиты
     * приказа об утверждении нархномы для справки маълумотнома (`malumotnoma_tariff_order`).
     * При недоступности — пустая карта (печать бланка не должна падать из-за настроек).
     */
    public Map<String, String> printSettings() {
        return settingsByCategory("print");
    }

    private Map<String, String> settingsByCategory(String category) {
        try {
            List<Map<String, Object>> rows = client.get()
                    .uri("/api/v1/settings?category=" + category)
                    .retrieve()
                    .body(LIST_OF_MAPS);
            if (rows == null) {
                return Map.of();
            }
            var map = new java.util.HashMap<String, String>();
            for (var row : rows) {
                Object key = row.get("settingKey");
                Object value = row.get("settingValue");
                if (key != null) {
                    map.put(key.toString(), value == null ? null : value.toString());
                }
            }
            return map;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /**
     * Классификатор WAYBILL_TYPE целиком (включая active=false): по нему сервис блокирует
     * отключённые администратором типы ПЛ. При недоступности master-data — пустой список
     * (fail-open: отключение типа — административное удобство, а не барьер безопасности).
     */
    public List<Map<String, Object>> waybillTypeClassifiers() {
        try {
            return list("/api/v1/classifiers?category=WAYBILL_TYPE&all=true");
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    // ------------------------------------------------------- справочники нормирования

    public List<Map<String, Object>> listFuelNorms() {
        return list("/api/v1/dictionaries/fuel-norms");
    }

    public List<Map<String, Object>> listCoefficients() {
        return list("/api/v1/dictionaries/coefficients");
    }

    public List<Map<String, Object>> listTariffs() {
        return list("/api/v1/dictionaries/tariffs");
    }

    // --------------------------- справочники расчётного ядра (перенос ИС «Роҳхат»)

    public List<Map<String, Object>> listWinterCoefs() {
        return list("/api/v1/legacy-ref/winter-coefs");
    }

    public List<Map<String, Object>> listMountainCoefs() {
        return list("/api/v1/legacy-ref/mountain-coefs");
    }

    public List<Map<String, Object>> listCityCoefs() {
        return list("/api/v1/legacy-ref/city-coefs");
    }

    public List<Map<String, Object>> listUsedCoefs() {
        return list("/api/v1/legacy-ref/used-coefs");
    }

    public List<Map<String, Object>> listDriveClasses() {
        return list("/api/v1/legacy-ref/drive-classes");
    }

    public List<Map<String, Object>> listBrands() {
        return list("/api/v1/legacy-ref/brands");
    }

    public List<Map<String, Object>> listDirections() {
        return list("/api/v1/legacy-ref/directions");
    }

    /** Направление грузовой перевозки по id; 404 → empty. */
    public Optional<Map<String, Object>> findDirection(long id) {
        try {
            Map<String, Object> direction = client.get()
                    .uri("/api/v1/legacy-ref/directions/{id}", id)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            return Optional.ofNullable(direction);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    /** Тарифы конкретного маршрута (нархнома). */
    public List<Map<String, Object>> listRouteTariffs(String routeId) {
        return list("/api/v1/legacy-ref/route-tariffs?routeId={id}", routeId);
    }

    /** Маршруты (с коэффициентными и путевыми полями V28). */
    public List<Map<String, Object>> listRoutes() {
        return list("/api/v1/dictionaries/routes");
    }

    /** Маршрут по номеру или названию (регистронезависимо); из маршрутов организации токена. */
    public Optional<Map<String, Object>> findRoute(String numberOrName) {
        if (numberOrName == null || numberOrName.isBlank()) {
            return Optional.empty();
        }
        String q = numberOrName.trim();
        List<Map<String, Object>> routes = listRoutes();
        return routes.stream()
                .filter(r -> q.equalsIgnoreCase(str(r.get("number"))) || q.equalsIgnoreCase(str(r.get("name"))))
                .findFirst();
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    /** Марка ТС по названию (регистронезависимо); 404 → empty. */
    public Optional<Map<String, Object>> findBrandByName(String name) {
        try {
            Map<String, Object> brand = client.get()
                    .uri("/api/v1/legacy-ref/brands?name={n}", name)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            return Optional.ofNullable(brand);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    /** Активные определения доп.полей (конструктор полей) для типа ПЛ — для серверной валидации обязательных. */
    public List<Map<String, Object>> listFieldDefinitions(String waybillType) {
        return list("/api/v1/field-definitions?waybillType={t}", waybillType);
    }

    private List<Map<String, Object>> list(String uri, Object... vars) {
        List<Map<String, Object>> list = client.get().uri(uri, vars).retrieve().body(LIST_OF_MAPS);
        return list == null ? List.of() : list;
    }

    private Optional<Map<String, Object>> first(String uri, Object... vars) {
        List<Map<String, Object>> list = client.get().uri(uri, vars).retrieve().body(LIST_OF_MAPS);
        return list == null || list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }
}
