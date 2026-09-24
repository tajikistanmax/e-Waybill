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
    /** Кэши справочников по контексту вызывающего (см. {@link TtlCache}); TTL 60 с. */
    private final TtlCache<List<Map<String, Object>>> routesCache = new TtlCache<>(java.time.Duration.ofSeconds(60));
    private final TtlCache<List<Map<String, Object>>> organizationsCache = new TtlCache<>(java.time.Duration.ofSeconds(60));
    private final TtlCache<Optional<Map<String, Object>>> directionsCache = new TtlCache<>(java.time.Duration.ofSeconds(60));
    /** Подпись водителя (data URI) для печати бланка — по РМА в контексте вызывающего; TTL 60 с. */
    /** Изображения для бланка (подписи, печати) — 60 с на вызывающего и документ. */
    private final TtlCache<Optional<String>> driverSignatureCache = new TtlCache<>(java.time.Duration.ofSeconds(60));
    /**
     * Марка ТС по названию и тарифы маршрута — TTL 60 с. Расчёт зовёт их по КАЖДОМУ путевому
     * листу отчёта: на сводном отчёте Минтранса за месяц это 52 895 обращений к master-data,
     * которые упирались в ограничитель частоты и роняли отчёт в 503 (находка приёмки 22.09.2026;
     * проявилась, когда отчёт стал учитывать архивные листы). Справочники марок и тарифов за
     * время построения одного отчёта не меняются.
     */
    private final TtlCache<List<Map<String, Object>>> brandsCache = new TtlCache<>(java.time.Duration.ofSeconds(60));
    private final TtlCache<List<Map<String, Object>>> routeTariffsCache = new TtlCache<>(java.time.Duration.ofSeconds(60));

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

    /**
     * Число ТС по организациям ({@code РМА → количество}), при {@code transportType != null} — только
     * этого вида. Одна агрегатная выборка master-data для «Норматива выдачи ПЛ» вместо списка ТС
     * каждой организации (N+1 по HTTP → 429 rate-limit).
     */
    public Map<String, Long> countVehiclesByOrganization(Integer transportType) {
        Map<String, Long> m = transportType == null
                ? client.get().uri("/api/v1/vehicles/count-by-organization").retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Long>>() {})
                : client.get().uri("/api/v1/vehicles/count-by-organization?transportType={t}", transportType).retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Long>>() {});
        return m == null ? Map.of() : m;
    }

    /** Организации, видимые вызывающему (master-data скоупит по токену); кэш 60 с на контекст вызывающего. */
    public List<Map<String, Object>> listOrganizations() {
        return organizationsCache.get(callerKey(), () -> list("/api/v1/organizations"));
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

    /**
     * Одобренная подпись водителя (документ вида SIGNATURE, master-data) как {@code data:image/...;base64,...}
     * для графы «Ронанда (имзо)» бланка — перенос legacy {@code drivers.signature_attach} (MIGRATION.md 2.6).
     * Любая ошибка (нет подписи, 403 тенанта/роли, недоступность) → empty: бланк печатается без изображения.
     */
    public Optional<String> findDriverSignatureDataUri(String rma) {
        if (rma == null || rma.isBlank()) {
            return Optional.empty();
        }
        return latestImage("/api/v1/drivers/{key}/documents/latest?docType=SIGNATURE", rma);
    }

    /**
     * Одобренное изображение сотрудника (врач, механик, диспетчер): {@code SIGNATURE} — подпись,
     * {@code SEAL} — личная печать врача («имзо, сикка»); legacy {@code employees.signature/seal}.
     */
    public Optional<String> findEmployeeImageDataUri(String rma, String docType) {
        if (rma == null || rma.isBlank()) {
            return Optional.empty();
        }
        return latestImage("/api/v1/employees/{key}/documents/latest?docType=" + docType, rma);
    }

    /** Одобренная печать организации (SEAL) — графа «Ҷои муҳри корхона»; legacy {@code companies.seal_attach}. */
    public Optional<String> findOrganizationSealDataUri(String rma) {
        if (rma == null || rma.isBlank()) {
            return Optional.empty();
        }
        return latestImage("/api/v1/organizations/{key}/documents/latest?docType=SEAL", rma);
    }

    /** Файл по адресу → data URI изображения; кэш 60 с на вызывающего. Любая ошибка → empty. */
    private Optional<String> latestImage(String uriTemplate, String key) {
        return driverSignatureCache.get(callerKey() + ":" + uriTemplate + ":" + key, () -> {
            try {
                var resp = client.get()
                        .uri(uriTemplate, key)
                        .retrieve()
                        .toEntity(byte[].class);
                var ct = resp.getHeaders().getContentType();
                return Optional.ofNullable(dataUri(ct == null ? null : ct.toString(), resp.getBody()));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        });
    }

    /** {@code data:<image/*>;base64,...} либо null, если это не изображение или тело пустое. */
    static String dataUri(String contentType, byte[] body) {
        if (body == null || body.length == 0 || contentType == null || !contentType.startsWith("image/")) {
            return null;
        }
        String ct = contentType.contains(";") ? contentType.substring(0, contentType.indexOf(';')).trim() : contentType.trim();
        return "data:" + ct + ";base64," + java.util.Base64.getEncoder().encodeToString(body);
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
        return brandsCache.get(callerKey(), () -> list("/api/v1/legacy-ref/brands"));
    }

    public List<Map<String, Object>> listDirections() {
        return list("/api/v1/legacy-ref/directions");
    }

    /** Тарифы конкретного маршрута (нархнома). */
    public List<Map<String, Object>> listRouteTariffs(String routeId) {
        if (routeId == null || routeId.isBlank()) {
            return List.of();
        }
        // Весь справочник одним запросом и отбор в памяти. Поштучный вызов
        // «?routeId=» в отчёте за месяц давал сотни обращений (по числу разных маршрутов)
        // и упирался в ограничитель частоты master-data — отчёт падал в 503 (приёмка 22.09.2026).
        return routeTariffsCache.get(callerKey(), () -> list("/api/v1/legacy-ref/route-tariffs")).stream()
                .filter(t -> routeId.equals(str(t.get("routeId"))))
                .toList();
    }

    /**
     * Маршруты (с коэффициентными и путевыми полями V28). Кэш 60 с на контекст вызывающего:
     * отчёты зовут {@link #findRoute} по каждому ПЛ, без кэша это сотни GET /dictionaries/routes
     * в минуту с одного IP и 429 от rate-limit master-data.
     */
    public List<Map<String, Object>> listRoutes() {
        return routesCache.get(callerKey(), () -> list("/api/v1/dictionaries/routes"));
    }

    /**
     * Направление (Самт) грузового ПЛ 2-Б по id — несёт id зимнего/горного/городского коэффициентов
     * (legacy {@code directions.winter_coef_id / mountain_coef_id / in_city_coef_id}); 404 → empty.
     * Кэш 60 с (расчёт по каждому грузовому ПЛ отчёта).
     */
    public Optional<Map<String, Object>> findDirection(long id) {
        return directionsCache.get(callerKey() + ":" + id, () -> {
            try {
                Map<String, Object> d = client.get()
                        .uri("/api/v1/legacy-ref/directions/{id}", id)
                        .retrieve()
                        .body(new ParameterizedTypeReference<Map<String, Object>>() {});
                return Optional.ofNullable(d);
            } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
                return Optional.empty();
            }
        });
    }

    /** Сброс кэшей справочников (после правки маршрутов/организаций через этот сервис или в тестах). */
    public void invalidateCaches() {
        routesCache.invalidateAll();
        organizationsCache.invalidateAll();
        directionsCache.invalidateAll();
        brandsCache.invalidateAll();
        routeTariffsCache.invalidateAll();
    }

    /**
     * Ключ кэша = контекст вызывающего: SHA-256 bearer-токена пользователя текущего запроса
     * (master-data скоупит списки по токену — ответы тенантов не смешиваются), иначе «service»
     * (client-credentials — платформенная область).
     */
    private String callerKey() {
        String userAuthorization = null;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            userAuthorization = attrs.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
        }
        if (userAuthorization == null || userAuthorization.isBlank()) {
            return "service";
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(userAuthorization.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(userAuthorization.hashCode());
        }
    }

    /** Маршрут по номеру или названию (регистронезависимо); из маршрутов организации токена. */
    public Optional<Map<String, Object>> findRoute(String numberOrName) {
        return findRoute(numberOrName, null);
    }

    /**
     * Маршрут для листа организации {@code organizationRma}: см. {@link #matchRoute}.
     * Маршрут в листе — свободный текст, диспетчер пишет «№8 Автовокзал — ТЦ «Садбарг»», а в
     * справочнике номер «8» и название отдельно; точное сравнение такой текст не узнавало, и в
     * отчётах у листа не было ни протяжённости, ни плановых рейсов (находка 23.09.2026).
     */
    public Optional<Map<String, Object>> findRoute(String numberOrName, String organizationRma) {
        if (numberOrName == null || numberOrName.isBlank()) {
            return Optional.empty();
        }
        return matchRoute(listRoutes(), numberOrName, organizationRma);
    }

    /**
     * Порядок: (1) точное совпадение номера или названия — маршруты своей организации впереди;
     * (2) текст начинается с номера («№8 …», «8 - …», «8/…») — только своя организация;
     * (3) текст содержит название маршрута (не короче 6 знаков, самое длинное) — только своя.
     * Без организации (2) и (3) не применяются — номер «8» есть у многих перевозчиков.
     */
    static Optional<Map<String, Object>> matchRoute(List<Map<String, Object>> routes, String text, String organizationRma) {
        String q = text.trim();
        List<Map<String, Object>> own = organizationRma == null ? List.of()
                : routes.stream().filter(r -> organizationRma.equals(str(r.get("organizationRma")))).toList();
        var exact = java.util.stream.Stream.concat(own.stream(), routes.stream())
                .filter(r -> q.equalsIgnoreCase(str(r.get("number")).trim()) || q.equalsIgnoreCase(str(r.get("name")).trim()))
                .findFirst();
        if (exact.isPresent() || own.isEmpty()) {
            return exact;
        }
        var m = java.util.regex.Pattern.compile("^\\s*(?:№|N|#|No\\.?)?\\s*([0-9A-Za-zА-Яа-я]{1,10}?)(?=$|[\\s\\-–—/,.:])")
                .matcher(q);
        if (m.find()) {
            String token = m.group(1);
            var byNumber = own.stream().filter(r -> token.equalsIgnoreCase(str(r.get("number")).trim())).findFirst();
            if (byNumber.isPresent()) {
                return byNumber;
            }
        }
        String lower = q.toLowerCase();
        return own.stream()
                .filter(r -> str(r.get("name")).trim().length() >= 6
                        && lower.contains(str(r.get("name")).trim().toLowerCase()))
                .max(java.util.Comparator.comparingInt(r -> str(r.get("name")).trim().length()));
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    /** Марка ТС по названию (регистронезависимо); 404 → empty. */
    public Optional<Map<String, Object>> findBrandByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        // Справочник марок (около 500 строк) забирается одним запросом и ищется в памяти.
        // Поштучный «?name=» в отчёте за месяц давал обращение на каждую встреченную марку:
        // кэш по имени не спасал — холодный кэш заполнялся быстрее, чем позволял ограничитель
        // частоты master-data, и отчёт падал в 503 (приёмка 22.09.2026).
        return matchBrand(listBrands(), name);
    }

    /**
     * Марка по тексту карточки ТС: точное название, иначе «название + модель» без учёта пробелов и
     * дефисов («ЛиАЗ-5292» = марка «ЛиАЗ», модель «5292»). В карточке ТС марка — свободный текст,
     * в справочнике — название и модель раздельно; без второго шага у такого ТС не находилась норма
     * расхода, и в отчётах норма была 0 (находка 23.09.2026). Префикс без модели не сопоставляется:
     * «МАЗ-203» с «МАЗ 103» дал бы чужую норму.
     */
    static Optional<Map<String, Object>> matchBrand(List<Map<String, Object>> brands, String name) {
        String needle = name.trim().toLowerCase();
        var exact = brands.stream()
                .filter(b -> needle.equals(str(b.get("name")).trim().toLowerCase()))
                .findFirst();
        if (exact.isPresent()) {
            return exact;
        }
        String compact = compactBrand(name);
        return brands.stream()
                .filter(b -> !str(b.get("model")).isBlank()
                        && compact.equals(compactBrand(str(b.get("name")) + str(b.get("model")))))
                .findFirst();
    }

    private static String compactBrand(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[\\s\\-_./]+", "");
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
