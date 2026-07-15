package tj.mintrans.epd.waybill.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.client.MasterDataClient;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.domain.WaybillStatusEvent;
import tj.mintrans.epd.waybill.domain.WaybillTitle;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.repository.WaybillStatusEventRepository;
import tj.mintrans.epd.waybill.repository.WaybillTitleRepository;
import tj.mintrans.epd.waybill.config.CurrentUser;
import tj.mintrans.epd.waybill.signing.TitleSigner;
import tj.mintrans.epd.waybill.web.error.ApiErrors.ConflictException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.ForbiddenException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Жизненный цикл путевого листа: блокирующие проверки (раздел 8.5 ТЗ),
 * титульная модель Т1–Т6 (раздел 8.2), статусная машина (раздел 8.4).
 */
@Service
public class WaybillService {

    private final WaybillRepository waybills;
    private final WaybillTitleRepository titles;
    private final WaybillStatusEventRepository events;
    private final tj.mintrans.epd.waybill.repository.WaybillPaymentRepository payments;
    private final MasterDataClient masterData;
    private final WaybillNumberGenerator numberGenerator;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final CurrentUser currentUser;
    private final TitleSigner titleSigner;
    /** Оплата выключена по умолчанию (dev/этап 1а); в проде — PAYMENT_ENABLED=true. */
    private final boolean paymentEnabled;
    private final java.math.BigDecimal paymentFee;

    public WaybillService(WaybillRepository waybills,
                          WaybillTitleRepository titles,
                          WaybillStatusEventRepository events,
                          tj.mintrans.epd.waybill.repository.WaybillPaymentRepository payments,
                          MasterDataClient masterData,
                          WaybillNumberGenerator numberGenerator,
                          org.springframework.context.ApplicationEventPublisher eventPublisher,
                          CurrentUser currentUser,
                          TitleSigner titleSigner,
                          @org.springframework.beans.factory.annotation.Value("${epd.payment.enabled:false}") boolean paymentEnabled,
                          @org.springframework.beans.factory.annotation.Value("${epd.payment.fee-somoni:10.00}") java.math.BigDecimal paymentFee) {
        this.waybills = waybills;
        this.titles = titles;
        this.events = events;
        this.payments = payments;
        this.masterData = masterData;
        this.numberGenerator = numberGenerator;
        this.eventPublisher = eventPublisher;
        this.currentUser = currentUser;
        this.titleSigner = titleSigner;
        this.paymentEnabled = paymentEnabled;
        this.paymentFee = paymentFee;
    }

    // ------------------------------------------------------------------ создание

    @Transactional
    public Waybill create(WaybillType type, String organizationRma, String vehicleRegNumber,
                          String driverRma, String secondDriverRma, String communicationType,
                          String route, String schedule, String specialMark,
                          Map<String, Object> typeData) {
        // Мультиарендность: tenant-scoped пользователь оформляет ПЛ только за свою организацию.
        if (currentUser.isTenantScoped()
                && !currentUser.organizationRma().map(rma -> rma.equals(organizationRma)).orElse(false)) {
            throw new ForbiddenException("Оформление путевого листа за другую организацию запрещено");
        }
        assertTypeEnabled(type);
        var org = masterData.findOrganization(organizationRma)
                .orElseThrow(() -> new NotFoundException("Организация не найдена"));
        var driver = masterData.findDriver(driverRma)
                .orElseThrow(() -> new NotFoundException("Водитель не найден"));
        var vehicle = masterData.findVehicle(vehicleRegNumber)
                .orElseThrow(() -> new NotFoundException("Транспорт не найден"));

        runBlockingChecks(org, driver, vehicle);
        assertDriverRested(driverRma, organizationRma, type);

        var wb = new Waybill();
        wb.setWaybillType(type);
        wb.setOrganizationRma(organizationRma);
        wb.setVehicleRegNumber(vehicleRegNumber);
        wb.setDriverRma(driverRma);
        if (communicationType != null) wb.setCommunicationType(communicationType);
        wb.setRoute(route);
        wb.setSchedule(schedule);
        wb.setSpecialMark(specialMark);
        // Снимки мастер-данных на момент оформления (принцип иммутабельности, раздел 11.1)
        wb.setOrganizationSnapshot(org);
        wb.setDriverSnapshot(driver);
        wb.setVehicleSnapshot(vehicle);
        validateTypeData(wb, typeData, secondDriverRma, org);
        var saved = waybills.save(wb);
        recordEvent(saved, null, WaybillStatus.DRAFT, "system", "Создан черновик");
        return saved;
    }

    /**
     * Валидация вариативных полей type_data по типу ПЛ (формы 2-Б, 5Б-БМ, 4М-БМ, 3-С;
     * spec/notes/01-legacy-api-и-формы.md, разделы 5.4–5.6). Записывает результат в wb.typeData.
     */
    private void validateTypeData(Waybill wb, Map<String, Object> typeData,
                                  String secondDriverRma, Map<String, Object> org) {
        var data = new java.util.LinkedHashMap<String, Object>();
        if (typeData != null) data.putAll(typeData);
        switch (wb.getWaybillType()) {
            case WB_TRUCK -> { // 2-Б грузовой
                String shipmentKind = str(data.get("shipmentKind"));
                if (shipmentKind.isBlank()) {
                    throw new UnprocessableException("Укажите вид перевозки (shipmentKind): PIECEWORK (корбайъ) или HOURLY (соатбайъ)");
                }
                if (!"PIECEWORK".equals(shipmentKind) && !"HOURLY".equals(shipmentKind)) {
                    throw new UnprocessableException("Недопустимый вид перевозки «%s»: ожидается PIECEWORK (корбайъ) или HOURLY (соатбайъ)".formatted(shipmentKind));
                }
                validateTrailers(data.get("trailers"));
                // Опасный груз (ДОПОГ) — режим грузового ПЛ, а не отдельный тип: при отметке
                // dangerous обязателен класс ADR (1–9). Свидетельства ADR (водитель/ТС) проверяются
                // мягко в preflight (как и раньше) — жёсткая блокировка появится с наполнением данных.
                if (Boolean.TRUE.equals(data.get("dangerous"))) {
                    String adrClass = str(data.get("adrClass"));
                    if (adrClass.isBlank()) {
                        throw new UnprocessableException("Для опасного груза укажите класс ADR (adrClass): 1–9");
                    }
                    if (!adrClass.matches("[1-9]")) {
                        throw new UnprocessableException("Недопустимый класс ADR «%s»: ожидается число 1–9".formatted(adrClass));
                    }
                }
            }
            case WB_TRUCK_INTL, WB_PAX_INTL -> { // 5Б-БМ / 4М-БМ международные
                requireText(data, "visaCountry", "Укажите страну выдачи визы (visaCountry)");
                requireText(data, "loadCountry", "Укажите страну погрузки (loadCountry)");
                requireText(data, "unloadCountry", "Укажите страну разгрузки (unloadCountry)");
                requireText(data, "permitNumber", "Укажите номер дозвола E-PERMIT (permitNumber)");
                // Онлайн-валидация дозвола в системе E-PERMIT (через единую платформу)
                var permit = masterData.findPermit(str(data.get("permitNumber")))
                        .orElseThrow(() -> new UnprocessableException(
                                "Дозвол %s не найден в системе E-PERMIT".formatted(str(data.get("permitNumber")))));
                if (!Boolean.TRUE.equals(permit.get("valid"))) {
                    throw new UnprocessableException("Дозвол %s недействителен или просрочен (E-PERMIT)"
                            .formatted(str(data.get("permitNumber"))));
                }
                var permitValidTo = dateOrNull(permit.get("validTo"));
                if (permitValidTo != null && permitValidTo.isBefore(LocalDate.now())) {
                    throw new UnprocessableException("Срок действия дозвола %s истёк (E-PERMIT)"
                            .formatted(str(data.get("permitNumber"))));
                }
                // Дозвол выдаётся для конкретной страны: он должен относиться к заявленному рейсу
                // (страна погрузки/разгрузки/транзита). Иначе действующий дозвол на одну страну
                // проходил бы для рейса в любую другую. Сверяем без учёта регистра/пробелов.
                String permitCountry = str(permit.get("country"));
                if (!permitCountry.isBlank()) {
                    var tripCountries = new java.util.HashSet<String>();
                    tripCountries.add(str(data.get("loadCountry")).trim().toLowerCase());
                    tripCountries.add(str(data.get("unloadCountry")).trim().toLowerCase());
                    if (data.get("transitCountries") instanceof java.util.List<?> tl) {
                        for (Object c : tl) {
                            if (c instanceof String s) tripCountries.add(s.trim().toLowerCase());
                        }
                    }
                    if (!tripCountries.contains(permitCountry.trim().toLowerCase())) {
                        throw new UnprocessableException(
                                "Дозвол %s выдан для страны «%s», не заявленной в рейсе (погрузка/разгрузка/транзит)"
                                        .formatted(str(data.get("permitNumber")), permitCountry));
                    }
                }
                if (wb.getWaybillType() == WaybillType.WB_TRUCK_INTL) {
                    requireText(data, "cargoName", "Укажите наименование груза (cargoName)");
                }
                String visaValidTo = str(data.get("visaValidTo"));
                if (visaValidTo.isBlank()) {
                    throw new UnprocessableException("Укажите срок действия визы (visaValidTo, ISO-дата)");
                }
                LocalDate visaDate;
                try {
                    visaDate = LocalDate.parse(visaValidTo);
                } catch (java.time.format.DateTimeParseException e) {
                    throw new UnprocessableException("Неверный формат срока действия визы (visaValidTo): ожидается ISO-дата, например 2026-12-31");
                }
                if (visaDate.isBefore(LocalDate.now())) {
                    throw new UnprocessableException("Срок визы истёк");
                }
                Object transit = data.get("transitCountries");
                if (transit != null) {
                    if (!(transit instanceof java.util.List<?> list) || list.stream().anyMatch(e -> !(e instanceof String))) {
                        throw new UnprocessableException("Транзитные страны (transitCountries): ожидается массив строк");
                    }
                }
                if (secondDriverRma != null && !secondDriverRma.isBlank()) {
                    var second = masterData.findDriver(secondDriverRma)
                            .orElseThrow(() -> new NotFoundException("Второй водитель не найден"));
                    if (!str(org.get("id")).equals(str(second.get("organizationId")))) {
                        throw new UnprocessableException("Второй водитель не принадлежит организации");
                    }
                    if (!waybills.findByDriverRmaAndStatusIn(str(second.get("rma")), WaybillStatus.OPEN_STATUSES).isEmpty()) {
                        throw new ConflictException("На второго водителя уже оформлен действующий путевой лист");
                    }
                    wb.setSecondDriverRma(secondDriverRma);
                    data.put("secondDriverSnapshot", second);
                }
            }
            case WB_CAR, WB_TAXI -> { // 3-С легковой/такси
                String serviceKind = str(data.get("serviceKind"));
                if (serviceKind.isBlank()) {
                    throw new UnprocessableException("Укажите вид услуги (serviceKind): TAXI (такси), ROUTE (хатсайр) или HOURLY (соатбай)");
                }
                if (!"TAXI".equals(serviceKind) && !"ROUTE".equals(serviceKind) && !"HOURLY".equals(serviceKind)) {
                    throw new UnprocessableException("Недопустимый вид услуги «%s»: ожидается TAXI, ROUTE или HOURLY".formatted(serviceKind));
                }
                if ("ROUTE".equals(serviceKind) && (wb.getRoute() == null || wb.getRoute().isBlank())) {
                    throw new UnprocessableException("Для маршрутной услуги укажите маршрут");
                }
            }
            case WB_DANGEROUS -> { // опасные грузы (ADR)
                String adrClass = str(data.get("adrClass"));
                if (adrClass.isBlank()) {
                    throw new UnprocessableException("Укажите класс опасного груза ADR (adrClass): 1–9");
                }
                if (!adrClass.matches("[1-9]")) {
                    throw new UnprocessableException("Недопустимый класс ADR «%s»: ожидается число 1–9".formatted(adrClass));
                }
            }
            case WB_SPECIAL -> { // спецтехника (форма 09) — учёт по МОТОЧАСАМ, не по километражу
                // Спецтехника (экскаватор/кран/погрузчик/каток/…) часто работает на площадке:
                // одометр не отражает работу, показатель — моточасы; вместо маршрута — вид работ
                // и объект. Одометр/маршрут для этого типа необязательны.
                requireText(data, "workType", "Укажите вид работ (workType)");
                double mhExit = parseMotorHours(data.get("motorHoursExit"),
                        "Укажите моточасы на выезде (motorHoursExit)");
                if (mhExit < 0) {
                    throw new UnprocessableException("Моточасы на выезде не могут быть отрицательными");
                }
                // workObject (объект/адрес работ) — рекомендуется, но не обязателен
            }
            default -> { /* прочие типы — свободная схема type_data */ }
        }
        validateCustomFields(wb.getWaybillType(), data);
        wb.setTypeData(data.isEmpty() ? null : data);
    }

    /**
     * Серверная проверка обязательных доп.полей типа ПЛ (конструктор полей, master-data):
     * API-клиент/интегратор не должен обойти обязательность, заданную администратором
     * (на фронте она уже проверяется). Применяется только к порталу/стандартному create —
     * не к legacy-агрегатору (тот идёт мимо validateTypeData). Значения — в data.custom.
     */
    private void validateCustomFields(WaybillType type, Map<String, Object> data) {
        var fieldDefs = masterData.listFieldDefinitions(type.name());
        if (fieldDefs.isEmpty()) {
            return;
        }
        var custom = new java.util.HashMap<String, Object>();
        if (data.get("custom") instanceof Map<?, ?> m) {
            m.forEach((k, v) -> custom.put(String.valueOf(k), v));
        }
        for (var fd : fieldDefs) {
            if (Boolean.TRUE.equals(fd.get("required"))) {
                String key = str(fd.get("fieldKey"));
                Object value = custom.get(key);
                if (value == null || str(value).isBlank()) {
                    String label = str(fd.get("labelRu"));
                    throw new UnprocessableException(
                            "Обязательное дополнительное поле «%s» не заполнено".formatted(label.isBlank() ? key : label));
                }
            }
        }
    }

    /** Моточасы спецтехники (формы 09): дробное неотрицательное число; пустое — ошибка requiredMsg. */
    private static double parseMotorHours(Object v, String requiredMsg) {
        String s = str(v).trim();
        if (s.isBlank()) {
            throw new UnprocessableException(requiredMsg);
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new UnprocessableException("Моточасы должны быть числом (например 1240.5)");
        }
    }

    /** trailers формы 2-Б: не более 2 прицепов, у каждого обязательны госномер и марка. */
    private static void validateTrailers(Object trailers) {
        if (trailers == null) return;
        if (!(trailers instanceof java.util.List<?> list)) {
            throw new UnprocessableException("Прицепы (trailers): ожидается массив объектов");
        }
        if (list.size() > 2) {
            throw new UnprocessableException("Не более 2 прицепов");
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> trailer)
                    || str(trailer.get("registrationNumber")).isBlank()
                    || str(trailer.get("brand")).isBlank()) {
                throw new UnprocessableException("Прицеп: госномер и марка обязательны");
            }
        }
    }

    private static void requireText(Map<String, Object> data, String field, String message) {
        if (str(data.get(field)).isBlank()) {
            throw new UnprocessableException(message);
        }
    }

    /** Блокирующие проверки перед выдачей (checks.yaml, подмножество этапа 1а). Package-private: переиспользуется AggregatorService. */
    void runBlockingChecks(Map<String, Object> org, Map<String, Object> driver, Map<String, Object> vehicle) {
        String orgId = str(org.get("id"));
        if (!orgId.equals(str(driver.get("organizationId")))) {
            throw new UnprocessableException("Водитель не принадлежит организации");
        }
        if (!orgId.equals(str(vehicle.get("organizationId")))) {
            throw new UnprocessableException("Транспорт не принадлежит организации");
        }
        if (Boolean.TRUE.equals(org.get("blocked"))) {
            throw new UnprocessableException("Организация заблокирована");
        }
        if (Boolean.TRUE.equals(driver.get("suspended"))) {
            throw new UnprocessableException("Водитель отстранён");
        }
        if (Boolean.TRUE.equals(vehicle.get("blocked"))) {
            throw new UnprocessableException("Транспорт заблокирован");
        }
        var today = LocalDate.now();
        // Лицензия обязательна для перевозчиков общего пользования (type_company = 1)
        if (Integer.valueOf(1).equals(intOrNull(org.get("typeCompany")))) {
            var licenseTo = dateOrNull(org.get("licenseTo"));
            if (licenseTo == null || licenseTo.isBefore(today)) {
                throw new UnprocessableException("Лицензия организации отсутствует или истекла");
            }
            var controlCard = dateOrNull(vehicle.get("controlCardValidTo"));
            if (controlCard == null || controlCard.isBefore(today)) {
                throw new UnprocessableException("Контрольная карточка ТС отсутствует или истекла");
            }
        }
        var licenseValidTo = dateOrNull(driver.get("licenseValidTo"));
        if (licenseValidTo != null && licenseValidTo.isBefore(today)) {
            throw new UnprocessableException("Срок действия водительского удостоверения истёк");
        }
        assertLicenseMatchesVehicle(driver, vehicle);
        var medCert = dateOrNull(driver.get("medCertValidTo"));
        if (medCert != null && medCert.isBefore(today)) {
            throw new UnprocessableException("Срок действия медицинской справки водителя истёк");
        }
        var techInspection = dateOrNull(vehicle.get("techInspectionValidTo"));
        if (techInspection == null || techInspection.isBefore(today)) {
            throw new UnprocessableException("Технический осмотр ТС отсутствует или истёк");
        }
        // Страховой полис ТС (§13): мягкая проверка — блокирует только истёкшая страховка
        // (отсутствие данных не блокирует, как срок ВУ/медсправки — пробел в реплике не рушит рейс).
        var insurance = dateOrNull(vehicle.get("insuranceValidTo"));
        if (insurance != null && insurance.isBefore(today)) {
            throw new UnprocessableException("Срок действия страхового полиса ТС истёк");
        }
        // Один активный ПЛ на ТС и на водителя
        if (!waybills.findByVehicleRegNumberAndStatusIn(str(vehicle.get("registrationNumber")), WaybillStatus.OPEN_STATUSES).isEmpty()) {
            throw new ConflictException("На это транспортное средство уже оформлен действующий путевой лист");
        }
        if (!waybills.findByDriverRmaAndStatusIn(str(driver.get("rma")), WaybillStatus.OPEN_STATUSES).isEmpty()) {
            throw new ConflictException("На этого водителя уже оформлен действующий путевой лист");
        }
        // Заблокированный инспектором документ не даёт оформить новый: требуется решение
        // администратора Минтранса (разблокировка или аннулирование). Нарочно НЕ в OPEN_STATUSES,
        // чтобы агрегатор не мог автоматически «закрыть» заблокированный ПЛ.
        var blocked = java.util.EnumSet.of(WaybillStatus.BLOCKED);
        if (!waybills.findByVehicleRegNumberAndStatusIn(str(vehicle.get("registrationNumber")), blocked).isEmpty()) {
            throw new ConflictException("На это ТС есть путевой лист, заблокированный инспектором, — требуется решение администратора Минтранса");
        }
        if (!waybills.findByDriverRmaAndStatusIn(str(driver.get("rma")), blocked).isEmpty()) {
            throw new ConflictException("На этого водителя есть путевой лист, заблокированный инспектором, — требуется решение администратора Минтранса");
        }
    }

    /**
     * Минимальный отдых водителя между рейсами (§5): новый ПЛ нельзя оформить, пока не прошёл
     * min_rest_hours после планового окончания последнего состоявшегося рейса (RETURNED/COMPLETED).
     * Правило из движка политик; 0/отсутствует — выключено (пробел не блокирует легальный рейс).
     */
    private void assertDriverRested(String driverRma, String organizationRma, WaybillType type) {
        int minRest;
        try {
            minRest = Integer.parseInt(String.valueOf(
                    masterData.effectivePolicies(organizationRma, type.name()).getOrDefault("min_rest_hours", "0")).trim());
        } catch (NumberFormatException e) {
            minRest = 0;
        }
        if (minRest <= 0) {
            return;
        }
        var ended = java.util.EnumSet.of(WaybillStatus.RETURNED, WaybillStatus.COMPLETED);
        var lastEnd = waybills.findByDriverRmaAndStatusIn(driverRma, ended).stream()
                .map(Waybill::getValidTo).filter(java.util.Objects::nonNull)
                .max(java.util.Comparator.naturalOrder()).orElse(null);
        if (lastEnd == null) {
            return;
        }
        var restUntil = lastEnd.plusHours(minRest);
        if (java.time.OffsetDateTime.now().isBefore(restUntil)) {
            throw new UnprocessableException(
                    "Не соблюдён минимальный отдых водителя (%d ч): следующий рейс не ранее %s"
                            .formatted(minRest, restUntil.toLocalDateTime()));
        }
    }

    /** Требуемая категория ВУ по типу ТС (справочник ЭПД 1..6): null — не проверяется. */
    private static String requiredLicenseCategory(Integer transportType) {
        if (transportType == null) {
            return null;
        }
        return switch (transportType) {
            case 1, 3 -> "D";   // автобус, микроавтобус (пассажирские перевозки)
            case 4 -> "B";      // легковой
            case 5, 6 -> "C";   // грузовой, грузовой международный
            default -> null;    // 2 = троллейбус (спецдопуск, не категория ВУ), прочее — не сверяем
        };
    }

    /**
     * ВУ↔тип ТС: у водителя должна быть категория, соответствующая типу ТС (безопасность —
     * неквалифицированный водитель не допускается к рейсу). Тип 2 (троллейбус) — спецдопуск,
     * не сверяем; при отсутствии данных о категориях проверка мягко пропускается (как срок ВУ),
     * чтобы пробел в реплике не блокировал легальный рейс.
     */
    private static void assertLicenseMatchesVehicle(Map<String, Object> driver, Map<String, Object> vehicle) {
        if (driver == null || vehicle == null) {
            return;
        }
        String required = requiredLicenseCategory(intOrNull(vehicle.get("transportType")));
        if (required == null) {
            return;
        }
        String categories = str(driver.get("licenseCategories"));
        if (categories.isBlank()) {
            return;
        }
        for (String token : categories.split("[,;\\s]+")) {
            if (token.equalsIgnoreCase(required)) {
                return;
            }
        }
        throw new UnprocessableException(
                "Категория водительского удостоверения не соответствует типу ТС: требуется «%s»".formatted(required));
    }

    // ---------------------------------------------------- пригодность (preflight, read-only)

    /** Одна причина пригодности: severity ERROR (недоступно) | WARN (предупреждение). */
    public record CheckResult(String code, String severity, String message) {}
    /** Результат проверки пригодности (тип, организация, ТС, водитель). */
    public record EligibilityResult(boolean eligible, java.util.List<CheckResult> checks) {}
    /** Доступность типа ПЛ для организации (уровень лицензии/вида субъекта). */
    public record TypeAvailability(String type, boolean available, java.util.List<String> reasons) {}

    /** Типы ПЛ общего пользования — недоступны организации «для собственных нужд» (type_company=2). */
    private static final java.util.Set<WaybillType> PUBLIC_SERVICE_TYPES = java.util.EnumSet.of(
            WaybillType.WB_TAXI, WaybillType.WB_BUS, WaybillType.WB_TROLLEYBUS, WaybillType.WB_MINIBUS);

    /** Допустимые виды ТС (transport_type 1..6) для типа ПЛ; null — не ограничиваем (спецтехника). */
    private static java.util.Set<Integer> allowedTransportTypes(WaybillType type) {
        return switch (type) {
            case WB_BUS -> java.util.Set.of(1);
            case WB_TROLLEYBUS -> java.util.Set.of(2);
            case WB_MINIBUS -> java.util.Set.of(3);
            case WB_CAR, WB_TAXI -> java.util.Set.of(4);
            case WB_TRUCK -> java.util.Set.of(5);
            case WB_TRUCK_INTL, WB_DANGEROUS -> java.util.Set.of(5, 6);
            case WB_PAX_INTL -> java.util.Set.of(1, 3);
            case WB_SPECIAL -> null; // спецтехника — вид ТС не ограничиваем
        };
    }

    private static boolean isCargo(WaybillType type) {
        return type == WaybillType.WB_TRUCK || type == WaybillType.WB_TRUCK_INTL
                || type == WaybillType.WB_DANGEROUS || type == WaybillType.WB_SPECIAL;
    }

    /**
     * Пригодность (preflight) для (тип, организация, ТС, водитель) — read-only, для мастера создания:
     * диспетчер видит «Доступен/Недоступно + причина» ДО создания. Ничего не меняет.
     */
    public EligibilityResult preflight(WaybillType type, String organizationRma,
                                       String vehicleRegNumber, String driverRma) {
        if (currentUser.isTenantScoped()
                && !currentUser.organizationRma().map(rma -> rma.equals(organizationRma)).orElse(false)) {
            throw new ForbiddenException("Проверка пригодности за другую организацию запрещена");
        }
        var org = masterData.findOrganization(organizationRma).orElse(null);
        var driver = (driverRma == null || driverRma.isBlank())
                ? null : masterData.findDriver(driverRma).orElse(null);
        var vehicle = (vehicleRegNumber == null || vehicleRegNumber.isBlank())
                ? null : masterData.findVehicle(vehicleRegNumber).orElse(null);
        var checks = collectEligibility(type, org, driver, vehicle);
        // Тип отключён администратором — недоступен независимо от лицензии/документов.
        if (disabledTypes().contains(type.name())) {
            checks.add(0, new CheckResult("TYPE_DISABLED", "ERROR", TYPE_DISABLED_MESSAGE));
        }
        boolean eligible = checks.stream().noneMatch(c -> "ERROR".equals(c.severity()));
        return new EligibilityResult(eligible, checks);
    }

    /**
     * Доступные типы ПЛ для организации по лицензии/виду субъекта — read-only, для шага выбора типа:
     * диспетчер видит, какие путевые листы может дать исходя из лицензии организации.
     */
    public java.util.List<TypeAvailability> availableTypes(String organizationRma) {
        if (currentUser.isTenantScoped()
                && !currentUser.organizationRma().map(rma -> rma.equals(organizationRma)).orElse(false)) {
            throw new ForbiddenException("Просмотр типов за другую организацию запрещён");
        }
        var org = masterData.findOrganization(organizationRma).orElse(null);
        var disabled = disabledTypes();
        var result = new java.util.ArrayList<TypeAvailability>();
        for (var type : WaybillType.values()) {
            if (disabled.contains(type.name())) {
                // Тип отключён администратором платформы (классификатор WAYBILL_TYPE, active=false)
                result.add(new TypeAvailability(type.name(), false, java.util.List.of(TYPE_DISABLED_MESSAGE)));
                continue;
            }
            var checks = collectEligibility(type, org, null, null); // только уровень лицензии/субъекта
            boolean available = checks.stream().noneMatch(c -> "ERROR".equals(c.severity()));
            result.add(new TypeAvailability(type.name(), available,
                    checks.stream().map(CheckResult::message).toList()));
        }
        return result;
    }

    static final String TYPE_DISABLED_MESSAGE = "Тип путевого листа отключён администратором платформы";

    /**
     * Коды типов ПЛ, отключённых администратором (настройки → «Типы путевых листов»):
     * классификатор WAYBILL_TYPE с active=false. При недоступности master-data — пусто (все разрешены).
     */
    private java.util.Set<String> disabledTypes() {
        var out = new java.util.HashSet<String>();
        for (var row : masterData.waybillTypeClassifiers()) {
            if (Boolean.FALSE.equals(row.get("active")) && row.get("code") != null) {
                out.add(row.get("code").toString());
            }
        }
        return out;
    }

    /** Оформление ПЛ отключённого типа запрещено — единая проверка для создания и одобрения заявки. */
    void assertTypeEnabled(WaybillType type) {
        if (disabledTypes().contains(type.name())) {
            throw new UnprocessableException(TYPE_DISABLED_MESSAGE);
        }
    }

    /**
     * Read-only сбор причин пригодности. НАМЕРЕННО зеркалит блокирующие проверки runBlockingChecks
     * (она остаётся единственным авторитетным местом при создании), плюс добавляет проактивные
     * сигналы (тип↔лицензия, тип↔вид ТС, курс БДД, ADR) для показа диспетчеру. Ничего не бросает.
     * driver/vehicle могут быть null (уровень лицензии — для выбора типа).
     */
    private java.util.List<CheckResult> collectEligibility(WaybillType type, Map<String, Object> org,
                                                           Map<String, Object> driver, Map<String, Object> vehicle) {
        var out = new java.util.ArrayList<CheckResult>();
        if (org == null) {
            out.add(new CheckResult("ORG_NOT_FOUND", "ERROR", "Организация не найдена"));
            return out;
        }
        var today = LocalDate.now();
        Integer typeCompany = intOrNull(org.get("typeCompany"));
        String orgId = str(org.get("id"));
        if (Boolean.TRUE.equals(org.get("blocked"))) {
            out.add(new CheckResult("ORG_BLOCKED", "ERROR", "Организация заблокирована"));
        }
        // Тип ПЛ ↔ вид субъекта: ведомственная организация (собственные нужды) не оказывает
        // перевозки общего пользования (такси, городской пассажирский).
        if (Integer.valueOf(2).equals(typeCompany) && PUBLIC_SERVICE_TYPES.contains(type)) {
            out.add(new CheckResult("TYPE_NOT_FOR_OWN_USE", "WARN",
                    "Для собственных нужд (ведомственная организация) перевозки общего пользования обычно недоступны"));
        }
        // Лицензия перевозчика общего пользования (type_company=1) — как в runBlockingChecks.
        if (Integer.valueOf(1).equals(typeCompany)) {
            var licenseTo = dateOrNull(org.get("licenseTo"));
            if (licenseTo == null || licenseTo.isBefore(today)) {
                out.add(new CheckResult("LICENSE_EXPIRED", "ERROR", "Лицензия организации отсутствует или истекла"));
            }
        }
        if (vehicle != null) {
            if (!orgId.equals(str(vehicle.get("organizationId")))) {
                out.add(new CheckResult("VEHICLE_FOREIGN", "ERROR", "Транспорт не принадлежит организации"));
            }
            if (Boolean.TRUE.equals(vehicle.get("blocked"))) {
                out.add(new CheckResult("VEHICLE_BLOCKED", "ERROR", "Транспорт заблокирован"));
            }
            // Тип ПЛ ↔ вид ТС (например, автобусный ПЛ на грузовом ТС).
            var allowed = allowedTransportTypes(type);
            Integer tt = intOrNull(vehicle.get("transportType"));
            if (allowed != null && tt != null && !allowed.contains(tt)) {
                out.add(new CheckResult("TYPE_VEHICLE_MISMATCH", "WARN",
                        "Тип путевого листа не соответствует виду выбранного ТС"));
            }
            var techInspection = dateOrNull(vehicle.get("techInspectionValidTo"));
            if (techInspection == null || techInspection.isBefore(today)) {
                out.add(new CheckResult("TECH_INSPECTION", "ERROR", "Технический осмотр ТС отсутствует или истёк"));
            }
            if (Integer.valueOf(1).equals(typeCompany)) {
                var controlCard = dateOrNull(vehicle.get("controlCardValidTo"));
                if (controlCard == null || controlCard.isBefore(today)) {
                    out.add(new CheckResult("CONTROL_CARD", "ERROR", "Контрольная карточка ТС отсутствует или истекла"));
                }
            }
            var insurance = dateOrNull(vehicle.get("insuranceValidTo"));
            if (insurance != null && insurance.isBefore(today)) {
                out.add(new CheckResult("INSURANCE_EXPIRED", "ERROR", "Срок действия страхового полиса ТС истёк"));
            }
            if (!waybills.findByVehicleRegNumberAndStatusIn(
                    str(vehicle.get("registrationNumber")), WaybillStatus.OPEN_STATUSES).isEmpty()) {
                out.add(new CheckResult("VEHICLE_HAS_ACTIVE", "ERROR", "На это ТС уже оформлен действующий путевой лист"));
            }
        }
        if (driver != null) {
            if (!orgId.equals(str(driver.get("organizationId")))) {
                out.add(new CheckResult("DRIVER_FOREIGN", "ERROR", "Водитель не принадлежит организации"));
            }
            if (Boolean.TRUE.equals(driver.get("suspended"))) {
                out.add(new CheckResult("DRIVER_SUSPENDED", "ERROR", "Водитель отстранён"));
            }
            var licenseValidTo = dateOrNull(driver.get("licenseValidTo"));
            if (licenseValidTo != null && licenseValidTo.isBefore(today)) {
                out.add(new CheckResult("DL_EXPIRED", "ERROR", "Срок действия водительского удостоверения истёк"));
            }
            var medCert = dateOrNull(driver.get("medCertValidTo"));
            if (medCert != null && medCert.isBefore(today)) {
                out.add(new CheckResult("MED_CERT_EXPIRED", "ERROR", "Срок действия медицинской справки водителя истёк"));
            }
            // Курс БДД (20-часовые занятия) для пассажирских/грузовых перевозок — предупреждение.
            if (type.isPassenger() || isCargo(type)) {
                var safety = dateOrNull(driver.get("safetyCourseValidTo"));
                if (safety == null || safety.isBefore(today)) {
                    out.add(new CheckResult("SAFETY_COURSE", "WARN",
                            "Курс БДД (20-часовые занятия) у водителя отсутствует или истёк"));
                }
            }
            if (!waybills.findByDriverRmaAndStatusIn(
                    str(driver.get("rma")), WaybillStatus.OPEN_STATUSES).isEmpty()) {
                out.add(new CheckResult("DRIVER_HAS_ACTIVE", "ERROR", "На этого водителя уже оформлен действующий путевой лист"));
            }
        }
        // Категория ВУ ↔ вид ТС (как assertLicenseMatchesVehicle) — если известны и водитель, и ТС.
        if (driver != null && vehicle != null) {
            String required = requiredLicenseCategory(intOrNull(vehicle.get("transportType")));
            String categories = str(driver.get("licenseCategories"));
            if (required != null && !categories.isBlank()) {
                boolean has = false;
                for (String token : categories.split("[,;\\s]+")) {
                    if (token.equalsIgnoreCase(required)) { has = true; break; }
                }
                if (!has) {
                    out.add(new CheckResult("DRIVER_CATEGORY", "ERROR",
                            "Категория ВУ не соответствует типу ТС: требуется «%s»".formatted(required)));
                }
            }
        }
        // Опасные грузы (ADR/ДОПОГ): свидетельство водителя и допуск ТС — предупреждение при отсутствии/истечении.
        if (type == WaybillType.WB_DANGEROUS) {
            if (driver != null) {
                var adr = dateOrNull(driver.get("adrCertValidTo"));
                if (adr == null || adr.isBefore(today)) {
                    out.add(new CheckResult("ADR_DRIVER", "WARN",
                            "Опасные грузы: у водителя нет действующего свидетельства ADR (ДОПОГ)"));
                }
            }
            if (vehicle != null) {
                var adr = dateOrNull(vehicle.get("adrApprovalValidTo"));
                if (adr == null || adr.isBefore(today)) {
                    out.add(new CheckResult("ADR_VEHICLE", "WARN",
                            "Опасные грузы: у ТС нет действующего допуска ADR"));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ титулы

    /** Т1 — выпуск: подписывает диспетчер, ПЛ переходит в CREATED. */
    @Transactional
    public Waybill signT1(UUID id, String dispatcherRma, OffsetDateTime validFrom, Integer validityDays) {
        var wb = getForUpdate(id);
        requireStatus(wb, WaybillStatus.DRAFT);
        var dispatcher = requireEmployee(wb, dispatcherRma, 3, "Диспетчер");
        var from = validFrom != null ? validFrom : OffsetDateTime.now();
        // Лимит срока действия: легальный максимум типа ПЛ, который политика max_validity_days
        // (уровни NATIONAL/ORGANIZATION/VEHICLE_TYPE) может только УЖЕСТОЧИТЬ, но не превысить.
        int typeCap = wb.getWaybillType().maxValidityDays();
        int effectiveCap = typeCap;
        String maxDaysRule = masterData
                .effectivePolicies(wb.getOrganizationRma(), wb.getWaybillType().name())
                .get("max_validity_days");
        if (maxDaysRule != null) {
            try {
                int policyCap = Integer.parseInt(maxDaysRule.trim());
                if (policyCap >= 1) {
                    effectiveCap = Math.min(typeCap, policyCap);
                }
            } catch (NumberFormatException ignored) {
                // некорректное значение политики — остаётся легальный лимит типа
            }
        }
        int days = validityDays != null ? validityDays : effectiveCap;
        if (days < 1) {
            throw new UnprocessableException("Срок действия должен быть не менее 1 дня");
        }
        if (days > effectiveCap) {
            throw new UnprocessableException("Срок действия превышает лимит: %d дн.".formatted(effectiveCap));
        }
        wb.setValidFrom(from);
        wb.setValidTo(from.plusDays(days));
        wb.setDispatcherRma(dispatcherRma);
        addTitle(wb, "T1", dispatcherRma, "DISPATCHER", Map.of(
                "route", wb.getRoute() == null ? "" : wb.getRoute(),
                "schedule", wb.getSchedule() == null ? "" : wb.getSchedule(),
                "validFrom", from.toString(),
                "validTo", wb.getValidTo().toString(),
                "dispatcher", dispatcher.get("name")));
        transition(wb, WaybillStatus.CREATED, dispatcherRma, "Т1 подписан");
        return waybills.save(wb);
    }

    /** Т2/Т6 — медицинский осмотр. */
    @Transactional
    public Waybill confirmMed(UUID id, String doctorRma, boolean passed, Map<String, Object> indicators) {
        var wb = getForUpdate(id);
        var doctor = requireEmployee(wb, doctorRma, 1, "Врач");
        if (wb.getStatus() == WaybillStatus.CREATED || wb.getStatus() == WaybillStatus.TECH_REJECTED) {
            if (titles.existsByWaybillIdAndTitleTypeAndSignerRma(id, "T2", doctorRma) && wb.isMedPassed()) {
                throw new ConflictException("Этот сотрудник уже подтвердил данный путевой лист");
            }
            addTitle(wb, "T2", doctorRma, "DOCTOR", withVerdict(indicators, passed, doctor));
            if (passed) {
                wb.setMedPassed(true);
                maybeReady(wb, doctorRma);
            } else {
                wb.setMedPassed(false); // сброс: отклонённый медосмотр не должен пропускать ПЛ к READY
                transition(wb, WaybillStatus.MED_REJECTED, doctorRma, "Водитель не допущен");
            }
            return waybills.save(wb);
        }
        if (wb.getStatus() == WaybillStatus.RETURNED) { // послерейсовый (Т6)
            addTitle(wb, "T6", doctorRma, "DOCTOR", withVerdict(indicators, passed, doctor));
            return waybills.save(wb);
        }
        throw new ConflictException("Медосмотр невозможен в статусе " + wb.getStatus());
    }

    /** Т3 — предрейсовый технический контроль. */
    @Transactional
    public Waybill confirmTech(UUID id, String mechanicRma, boolean passed, Map<String, Object> checklist) {
        var wb = getForUpdate(id);
        if (wb.getStatus() != WaybillStatus.CREATED && wb.getStatus() != WaybillStatus.MED_REJECTED) {
            throw new ConflictException("Техконтроль невозможен в статусе " + wb.getStatus());
        }
        var mechanic = requireEmployee(wb, mechanicRma, 2, "Механик");
        if (titles.existsByWaybillIdAndTitleTypeAndSignerRma(id, "T3", mechanicRma) && wb.isTechPassed()) {
            throw new ConflictException("Этот сотрудник уже подтвердил данный путевой лист");
        }
        addTitle(wb, "T3", mechanicRma, "MECHANIC", withVerdict(checklist, passed, mechanic));
        if (passed) {
            wb.setTechPassed(true);
            maybeReady(wb, mechanicRma);
        } else {
            wb.setTechPassed(false); // сброс: отклонённый техконтроль не должен пропускать ПЛ к READY
            transition(wb, WaybillStatus.TECH_REJECTED, mechanicRma, "ТС неисправно");
        }
        return waybills.save(wb);
    }

    /**
     * Т2+Т3 выполнены → оплата (если включена и не покрыта) или сразу номер + READY.
     * Агрегаторские ПЛ (source=AGGREGATOR) считаются покрытыми абонементом агрегатора —
     * legacy-семантика ЧУРА/НЕРУ, оплата с них не требуется.
     */
    private void maybeReady(Waybill wb, String actor) {
        // Обязательность предрейсового медосмотра (Т2) и техконтроля (Т3) — из движка политик
        // (require_med_pre / require_tech_check, уровни NATIONAL/ORGANIZATION/VEHICLE_TYPE).
        // По умолчанию оба обязательны (законное требование); политика может снять требование
        // для отдельного типа ПЛ или организации.
        var policies = masterData.effectivePolicies(wb.getOrganizationRma(), wb.getWaybillType().name());
        boolean medRequired = !"false".equalsIgnoreCase(policies.get("require_med_pre"));
        boolean techRequired = !"false".equalsIgnoreCase(policies.get("require_tech_check"));
        if ((medRequired && !wb.isMedPassed()) || (techRequired && !wb.isTechPassed())) {
            return;
        }
        boolean paymentRequired = paymentEnabled
                && "PORTAL".equals(wb.getSource())
                && payments.findByWaybillId(wb.getId())
                        .map(p -> !tj.mintrans.epd.waybill.domain.WaybillPayment.STATUS_CONFIRMED.equals(p.getStatus()))
                        .orElse(true);
        if (paymentRequired) {
            if (payments.findByWaybillId(wb.getId()).isEmpty()) {
                var payment = new tj.mintrans.epd.waybill.domain.WaybillPayment();
                payment.setWaybillId(wb.getId());
                payment.setAmount(paymentFee);
                payments.save(payment);
            }
            transition(wb, WaybillStatus.AWAITING_PAYMENT, actor,
                    "Медосмотр и техконтроль пройдены; ожидается оплата %s TJS".formatted(paymentFee));
            return;
        }
        assignNumberAndReady(wb, actor);
    }

    /** Присвоение национального номера и переход в READY (номер → доступен QR). */
    private void assignNumberAndReady(Waybill wb, String actor) {
        Short regionId = wb.getOrganizationSnapshot() != null
                ? shortOrNull(wb.getOrganizationSnapshot().get("regionId")) : null;
        wb.setNumber(numberGenerator.next(regionId, wb.getWaybillType()));
        transition(wb, WaybillStatus.READY, actor, "Медосмотр и техконтроль пройдены; номер присвоен");
    }

    // ------------------------------------------------------------------ оплата

    /** Карточка оплаты документа (для кабинета компании/бухгалтера). */
    public tj.mintrans.epd.waybill.domain.WaybillPayment getPayment(UUID waybillId) {
        get(waybillId); // 404, если ПЛ не существует
        return payments.findByWaybillId(waybillId)
                .orElseThrow(() -> new NotFoundException("Оплата по этому путевому листу не требуется"));
    }

    /**
     * Подтверждение оплаты (бухгалтер или платёжный шлюз):
     * AWAITING_PAYMENT → PAID → номер + READY (waybill-statuses.yaml).
     */
    @Transactional
    public Waybill confirmPayment(UUID id, String method, String externalRef, String actor) {
        var wb = getForUpdate(id);
        requireStatus(wb, WaybillStatus.AWAITING_PAYMENT);
        // Блокирующая загрузка платежа: сериализует одновременные доставки вебхука (идемпотентность).
        var payment = payments.findByWaybillIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Запись об оплате не найдена"));
        if (tj.mintrans.epd.waybill.domain.WaybillPayment.STATUS_CONFIRMED.equals(payment.getStatus())) {
            throw new ConflictException("Оплата уже подтверждена");
        }
        payment.setStatus(tj.mintrans.epd.waybill.domain.WaybillPayment.STATUS_CONFIRMED);
        payment.setMethod(method == null || method.isBlank() ? "BANK" : method);
        payment.setExternalRef(externalRef);
        payment.setConfirmedAt(OffsetDateTime.now());
        payment.setConfirmedBy(actor);
        payments.save(payment);
        transition(wb, WaybillStatus.PAID, actor,
                "Оплата %s %s подтверждена (%s)".formatted(payment.getAmount(), payment.getCurrency(), payment.getMethod()));
        assignNumberAndReady(wb, actor);
        return waybills.save(wb);
    }

    /** Выдача: водитель подтверждает получение (Face ID/PIN в мобильном кабинете). */
    @Transactional
    public Waybill issue(UUID id, String driverConfirmation) {
        var wb = getForUpdate(id);
        requireStatus(wb, WaybillStatus.READY);
        transition(wb, WaybillStatus.ISSUED, wb.getDriverRma(),
                "Водитель подтвердил получение (" + (driverConfirmation == null ? "PIN" : driverConfirmation) + ")");
        return waybills.save(wb);
    }

    /** Т4 — одометр/топливо на выезде; выезд на линию. */
    @Transactional
    public Waybill activate(UUID id, String dispatcherRma, Integer odometerExit) {
        var wb = getForUpdate(id);
        requireStatus(wb, WaybillStatus.ISSUED);
        requireEmployee(wb, dispatcherRma, 3, "Диспетчер");
        Integer lastKnown = wb.getVehicleSnapshot() != null
                ? intOrNull(wb.getVehicleSnapshot().get("odometer")) : null;
        // Непрерывность одометра (антифрод): явный выезд не может быть отрицательным и не может
        // быть меньше последнего зафиксированного пробега ТС — иначе это скрутка/подмена показаний.
        if (odometerExit != null) {
            if (odometerExit < 0) {
                throw new UnprocessableException("Одометр выезда не может быть отрицательным");
            }
            if (lastKnown != null && odometerExit < lastKnown) {
                throw new UnprocessableException(
                        "Одометр выезда (%d) меньше последнего зафиксированного пробега ТС (%d) — проверьте показания"
                                .formatted(odometerExit, lastKnown));
            }
        }
        int exit = odometerExit != null ? odometerExit
                : lastKnown != null ? lastKnown : 0;
        wb.setOdometerExit(exit);
        addTitle(wb, "T4", dispatcherRma, "DISPATCHER", Map.of("odometerExit", exit));
        transition(wb, WaybillStatus.ACTIVE, dispatcherRma, "Выезд на линию");
        return waybills.save(wb);
    }

    /** Т5 — возвращение: одометр возврата. */
    @Transactional
    public Waybill returnTrip(UUID id, String dispatcherRma, int odometerEntry) {
        return returnTrip(id, dispatcherRma, odometerEntry, null);
    }

    /** Возврат (Т5). Для спецтехники дополнительно фиксируются моточасы возврата (motorHoursEntry). */
    @Transactional
    public Waybill returnTrip(UUID id, String dispatcherRma, int odometerEntry, Double motorHoursEntry) {
        var wb = getForUpdate(id);
        requireStatus(wb, WaybillStatus.ACTIVE);
        requireEmployee(wb, dispatcherRma, 3, "Диспетчер");
        if (wb.getOdometerExit() != null && odometerEntry < wb.getOdometerExit()) {
            throw new UnprocessableException("Одометр возврата меньше одометра выезда");
        }
        wb.setOdometerEntry(odometerEntry);
        // Спецтехника: учёт по моточасам — фиксируем моточасы возврата в type_data,
        // отработано = возврат − выезд (проверяем непрерывность, как у одометра).
        if (wb.getWaybillType() == WaybillType.WB_SPECIAL && motorHoursEntry != null) {
            var td = wb.getTypeData() != null
                    ? new java.util.LinkedHashMap<String, Object>(wb.getTypeData())
                    : new java.util.LinkedHashMap<String, Object>();
            double exit = td.get("motorHoursExit") == null ? 0
                    : Double.parseDouble(str(td.get("motorHoursExit")));
            if (motorHoursEntry < 0) {
                throw new UnprocessableException("Моточасы возврата не могут быть отрицательными");
            }
            if (motorHoursEntry < exit) {
                throw new UnprocessableException("Моточасы возврата меньше моточасов выезда");
            }
            td.put("motorHoursEntry", motorHoursEntry);
            wb.setTypeData(td);
        }
        addTitle(wb, "T5", dispatcherRma, "DISPATCHER", Map.of(
                "odometerEntry", odometerEntry,
                "distance", wb.getOdometerExit() != null ? odometerEntry - wb.getOdometerExit() : 0));
        transition(wb, WaybillStatus.RETURNED, dispatcherRma, "Возвращение");
        return waybills.save(wb);
    }

    /**
     * Закрытие: требование послерейсового медосмотра (Т6) определяется движком политик
     * (правило require_med_post, уровни NATIONAL/ORGANIZATION/VEHICLE_TYPE). Если движок
     * недоступен — безопасный фолбэк на прежнее правило «обязателен для пассажирских перевозок».
     */
    @Transactional
    public Waybill close(UUID id, String actor) {
        var wb = getForUpdate(id);
        requireStatus(wb, WaybillStatus.RETURNED);
        var policies = masterData.effectivePolicies(wb.getOrganizationRma(), wb.getWaybillType().name());
        boolean requireMedPost = policies.containsKey("require_med_post")
                ? Boolean.parseBoolean(policies.get("require_med_post"))
                : wb.getWaybillType().isPassenger();
        if (requireMedPost && titles.findByWaybillIdAndTitleType(id, "T6").isEmpty()) {
            throw new ConflictException("Требуется послерейсовый медосмотр (Т6) перед закрытием путевого листа");
        }
        // Перенос одометра в мастер-данные для следующего ПЛ
        if (wb.getOdometerEntry() != null && wb.getVehicleSnapshot() != null) {
            masterData.updateVehicleOdometer(str(wb.getVehicleSnapshot().get("id")), wb.getOdometerEntry());
        }
        transition(wb, WaybillStatus.COMPLETED, actor, "Путевой лист закрыт");
        return waybills.save(wb);
    }

    @Transactional
    public Waybill cancel(UUID id, String reason, String actor) {
        var wb = getForUpdate(id);
        if (wb.getStatus().isTerminal()) {
            throw new ConflictException("Аннулирование невозможно в статусе " + wb.getStatus());
        }
        // Заблокированный инспектором ПЛ нельзя аннулировать в обход блокировки —
        // сначала разблокировка (только SYSTEM_ADMIN/Минтранс), затем аннулирование.
        if (wb.getStatus() == WaybillStatus.BLOCKED) {
            throw new ConflictException("Заблокированный путевой лист аннулировать нельзя — требуется разблокировка Минтрансом");
        }
        wb.setCancelReason(reason);
        transition(wb, WaybillStatus.CANCELLED, actor, reason);
        return waybills.save(wb);
    }

    // -------------------------------------------- корректирующие титулы (замена)

    /**
     * Замена водителя после недопуска: MED_REJECTED → CREATED (waybill-statuses.yaml).
     * Подписанные титулы неизменяемы — замена оформляется титулом CORRECTION.
     */
    @Transactional
    public Waybill replaceDriver(UUID id, String newDriverRma, String dispatcherRma) {
        var wb = getForUpdate(id);
        requireStatus(wb, WaybillStatus.MED_REJECTED);
        requireEmployee(wb, dispatcherRma, 3, "Диспетчер");
        var driver = masterData.findDriver(newDriverRma)
                .orElseThrow(() -> new NotFoundException("Водитель не найден"));
        if (!str(wb.getOrganizationSnapshot().get("id")).equals(str(driver.get("organizationId")))) {
            throw new UnprocessableException("Новый водитель не принадлежит организации");
        }
        if (Boolean.TRUE.equals(driver.get("suspended"))) {
            throw new UnprocessableException("Новый водитель отстранён");
        }
        var today = LocalDate.now();
        var licenseValidTo = dateOrNull(driver.get("licenseValidTo"));
        if (licenseValidTo != null && licenseValidTo.isBefore(today)) {
            throw new UnprocessableException("Срок действия водительского удостоверения нового водителя истёк");
        }
        var medCert = dateOrNull(driver.get("medCertValidTo"));
        if (medCert != null && medCert.isBefore(today)) {
            throw new UnprocessableException("Срок действия медицинской справки нового водителя истёк");
        }
        // Категория ВУ нового водителя должна подходить типу существующего ТС.
        assertLicenseMatchesVehicle(driver, wb.getVehicleSnapshot());
        if (!waybills.findByDriverRmaAndStatusIn(newDriverRma, WaybillStatus.OPEN_STATUSES).isEmpty()) {
            throw new ConflictException("На нового водителя уже оформлен действующий путевой лист");
        }
        // Как при создании (runBlockingChecks): водитель с ПЛ, заблокированным инспектором,
        // не подставляется заменой — требуется решение администратора Минтранса. Без этой
        // проверки корректирующий титул обходил бы запрет create-пути.
        if (!waybills.findByDriverRmaAndStatusIn(newDriverRma, java.util.EnumSet.of(WaybillStatus.BLOCKED)).isEmpty()) {
            throw new ConflictException("На нового водителя есть путевой лист, заблокированный инспектором, — требуется решение администратора Минтранса");
        }
        // Минимальный отдых (§5) действует и при замене — иначе политика обходится корректировкой.
        assertDriverRested(newDriverRma, wb.getOrganizationRma(), wb.getWaybillType());
        String oldDriverRma = wb.getDriverRma();
        wb.setDriverRma(newDriverRma);
        wb.setDriverSnapshot(driver);
        wb.setMedPassed(false); // новый водитель проходит медосмотр заново
        addTitle(wb, "CORRECTION", dispatcherRma, "DISPATCHER", Map.of(
                "action", "REPLACE_DRIVER",
                "oldDriverRma", oldDriverRma,
                "newDriverRma", newDriverRma,
                "newDriverName", str(driver.get("fullName"))));
        transition(wb, WaybillStatus.CREATED, dispatcherRma,
                "Замена водителя %s → %s (корректирующий титул)".formatted(oldDriverRma, newDriverRma));
        return waybills.save(wb);
    }

    /**
     * Замена ТС после отклонения техконтролем: TECH_REJECTED → CREATED
     * (waybill-statuses.yaml). Оформляется титулом CORRECTION.
     */
    @Transactional
    public Waybill replaceVehicle(UUID id, String newVehicleRegNumber, String dispatcherRma) {
        var wb = getForUpdate(id);
        requireStatus(wb, WaybillStatus.TECH_REJECTED);
        requireEmployee(wb, dispatcherRma, 3, "Диспетчер");
        var vehicle = masterData.findVehicle(newVehicleRegNumber)
                .orElseThrow(() -> new NotFoundException("Транспорт не найден"));
        if (!str(wb.getOrganizationSnapshot().get("id")).equals(str(vehicle.get("organizationId")))) {
            throw new UnprocessableException("Новое ТС не принадлежит организации");
        }
        if (Boolean.TRUE.equals(vehicle.get("blocked"))) {
            throw new UnprocessableException("Новое ТС заблокировано");
        }
        var today = LocalDate.now();
        var techInspection = dateOrNull(vehicle.get("techInspectionValidTo"));
        if (techInspection == null || techInspection.isBefore(today)) {
            throw new UnprocessableException("Технический осмотр нового ТС отсутствует или истёк");
        }
        // Страховой полис (§13) — как при создании: истёкшая страховка блокирует замену
        // (отсутствие данных не блокирует — пробел в реплике не рушит корректировку).
        var insurance = dateOrNull(vehicle.get("insuranceValidTo"));
        if (insurance != null && insurance.isBefore(today)) {
            throw new UnprocessableException("Срок действия страхового полиса нового ТС истёк");
        }
        if (Integer.valueOf(1).equals(intOrNull(wb.getOrganizationSnapshot().get("typeCompany")))) {
            var controlCard = dateOrNull(vehicle.get("controlCardValidTo"));
            if (controlCard == null || controlCard.isBefore(today)) {
                throw new UnprocessableException("Контрольная карточка нового ТС отсутствует или истекла");
            }
        }
        // Тип нового ТС должен соответствовать категории ВУ существующего водителя.
        assertLicenseMatchesVehicle(wb.getDriverSnapshot(), vehicle);
        if (!waybills.findByVehicleRegNumberAndStatusIn(newVehicleRegNumber, WaybillStatus.OPEN_STATUSES).isEmpty()) {
            throw new ConflictException("На новое ТС уже оформлен действующий путевой лист");
        }
        // Как при создании (runBlockingChecks): ТС с ПЛ, заблокированным инспектором,
        // не подставляется заменой — требуется решение администратора Минтранса.
        if (!waybills.findByVehicleRegNumberAndStatusIn(newVehicleRegNumber, java.util.EnumSet.of(WaybillStatus.BLOCKED)).isEmpty()) {
            throw new ConflictException("На новое ТС есть путевой лист, заблокированный инспектором, — требуется решение администратора Минтранса");
        }
        String oldVehicle = wb.getVehicleRegNumber();
        wb.setVehicleRegNumber(newVehicleRegNumber);
        wb.setVehicleSnapshot(vehicle);
        wb.setTechPassed(false); // новое ТС проходит техконтроль заново
        addTitle(wb, "CORRECTION", dispatcherRma, "DISPATCHER", Map.of(
                "action", "REPLACE_VEHICLE",
                "oldVehicleRegNumber", oldVehicle,
                "newVehicleRegNumber", newVehicleRegNumber,
                "newVehicleBrand", str(vehicle.get("brand"))));
        transition(wb, WaybillStatus.CREATED, dispatcherRma,
                "Замена ТС %s → %s (корректирующий титул)".formatted(oldVehicle, newVehicleRegNumber));
        return waybills.save(wb);
    }

    // -------------------------------------------- дорожный контроль (инспектор)

    /** Блокировка при нарушении на дорожном контроле: ACTIVE → BLOCKED (роль INSPECTOR). */
    @Transactional
    public Waybill block(UUID id, String reason, String actor) {
        var wb = getForUpdate(id);
        requireStatus(wb, WaybillStatus.ACTIVE);
        transition(wb, WaybillStatus.BLOCKED, actor, reason);
        return waybills.save(wb);
    }

    /** Разблокировка администратором Минтранса (с обоснованием, аудит): BLOCKED → ACTIVE. */
    @Transactional
    public Waybill unblock(UUID id, String reason, String actor) {
        var wb = getForUpdate(id);
        requireStatus(wb, WaybillStatus.BLOCKED);
        transition(wb, WaybillStatus.ACTIVE, actor, reason);
        return waybills.save(wb);
    }

    // ------------------------------------------------------------------ вспомогательные

    public Waybill get(UUID id) {
        return checkTenant(waybills.findById(id).orElseThrow(() -> new NotFoundException("Путевой лист не найден")));
    }

    /**
     * Как get(), но с блокировкой строки (FOR UPDATE) — все мутации ПЛ идут через этот
     * метод и сериализуются: конкурентные confirmMed/confirmTech не теряют флаги
     * medPassed/techPassed (lost update), двойные issue/transition не задваивают события.
     */
    Waybill getForUpdate(UUID id) {
        return checkTenant(waybills.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Путевой лист не найден")));
    }

    /**
     * Мультиарендность: tenant-scoped пользователь (не-админ) видит и меняет только ПЛ
     * своей организации. Все чтения и мутации проходят через get()/getForUpdate(),
     * поэтому проверка одна. 404 (а не 403) — чтобы не раскрывать существование чужого документа.
     */
    private Waybill checkTenant(Waybill wb) {
        if (currentUser.isTenantScoped()
                && !currentUser.organizationRma().map(rma -> rma.equals(wb.getOrganizationRma())).orElse(false)) {
            throw new NotFoundException("Путевой лист не найден");
        }
        // Водитель (роль DRIVER) видит только СВОИ рейсы — как основной или второй водитель.
        // Иначе через прямой GET /{id} и /{id}/qr он вытянул бы чужой ПЛ и подписанный QR
        // (тот же инвариант, что уже применяется в списке WaybillController.list()).
        if (currentUser.isTenantScoped() && currentUser.hasRole("DRIVER")) {
            String myRma = currentUser.rma().orElse(null);
            if (myRma == null
                    || (!myRma.equals(wb.getDriverRma()) && !myRma.equals(wb.getSecondDriverRma()))) {
                throw new NotFoundException("Путевой лист не найден");
            }
        }
        return wb;
    }

    private void requireStatus(Waybill wb, WaybillStatus expected) {
        if (wb.getStatus() != expected) {
            throw new ConflictException("Операция допустима только в статусе %s (текущий: %s)".formatted(expected, wb.getStatus()));
        }
    }

    private Map<String, Object> requireEmployee(Waybill wb, String rma, int type, String roleName) {
        var employee = masterData.findEmployee(rma)
                .orElseThrow(() -> new NotFoundException(roleName + " не найден"));
        if (!Integer.valueOf(type).equals(intOrNull(employee.get("type")))) {
            throw new UnprocessableException("Сотрудник %s не имеет роли «%s»".formatted(rma, roleName));
        }
        // Подписант титула — сотрудник организации ПЛ: роль вызывающего проверяет @PreAuthorize,
        // но без этой сверки титул (юридический след) можно было бы атрибутировать сотруднику
        // ЧУЖОЙ организации, передав его РМА в теле запроса.
        if (!str(wb.getOrganizationSnapshot().get("id")).equals(str(employee.get("organizationId")))) {
            throw new UnprocessableException("Сотрудник %s не принадлежит организации путевого листа".formatted(rma));
        }
        return employee;
    }

    /** Package-private: переиспользуется AggregatorService. */
    void addTitle(Waybill wb, String titleType, String signerRma, String signerRole, Map<String, Object> data) {
        var title = new WaybillTitle();
        title.setWaybillId(wb.getId());
        title.setTitleType(titleType);
        title.setSignerRma(signerRma);
        title.setSignerRole(signerRole);
        title.setData(data);
        // Подпись титула через TitleSigner (dev: SHA-256; prod: квалифицированная ЭП CAdES — УЦ РТ).
        title.setSignature(titleSigner.sign(wb.getId(), titleType, signerRma, data));
        titles.save(title);
    }

    /** Package-private: переиспользуется AggregatorService. */
    void transition(Waybill wb, WaybillStatus to, String actor, String reason) {
        var from = wb.getStatus();
        wb.setStatus(to);
        recordEvent(wb, from, to, actor, reason);
    }

    /**
     * Журнал статусов (append-only, БД) + доменное событие: после commit
     * KafkaEventBridge публикует его в topic epd.waybill.status —
     * для единого личного кабинета Минтранса, аналитики и антифрода.
     */
    private void recordEvent(Waybill wb, WaybillStatus from, WaybillStatus to, String actor, String reason) {
        events.save(WaybillStatusEvent.of(wb.getId(), from, to, actor, reason));
        eventPublisher.publishEvent(new tj.mintrans.epd.waybill.event.WaybillStatusChanged(
                wb.getId(), wb.getNumber(), wb.getWaybillType().name(),
                wb.getOrganizationRma(), wb.getVehicleRegNumber(), wb.getDriverRma(),
                from == null ? null : from.name(), to.name(),
                actor, reason, wb.getSource(), OffsetDateTime.now()));
    }

    private static Map<String, Object> withVerdict(Map<String, Object> data, boolean passed, Map<String, Object> employee) {
        var result = new java.util.LinkedHashMap<String, Object>();
        if (data != null) result.putAll(data);
        result.put("verdict", passed ? "ДОПУЩЕН" : "НЕ ДОПУЩЕН");
        result.put("employeeName", employee.get("name"));
        return result;
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    private static Integer intOrNull(Object o) {
        return o instanceof Number n ? n.intValue() : o != null ? Integer.valueOf(o.toString()) : null;
    }

    private static Short shortOrNull(Object o) {
        Integer i = intOrNull(o);
        return i == null ? null : i.shortValue();
    }

    private static LocalDate dateOrNull(Object o) {
        return o == null ? null : LocalDate.parse(o.toString());
    }
}
