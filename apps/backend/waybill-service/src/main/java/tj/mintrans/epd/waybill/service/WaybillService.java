package tj.mintrans.epd.waybill.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
import tj.mintrans.epd.waybill.config.CurrentUser;
import tj.mintrans.epd.waybill.config.TenantScope;
import tj.mintrans.epd.waybill.domain.InspectionReason;
import tj.mintrans.epd.waybill.domain.WaybillInspection;
import tj.mintrans.epd.waybill.repository.WaybillInspectionRepository;
import tj.mintrans.epd.waybill.signing.TitleSigner;
import tj.mintrans.epd.waybill.crypto.MedicalDataCrypto;
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
    private final BranchSerialGenerator branchSerial;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final TenantScope tenantScope;
    private final CurrentUser currentUser;
    private final WaybillInspectionRepository inspections;
    private final TitleSigner titleSigner;
    private final MedicalDataCrypto medicalCrypto;
    /** Оплата выключена по умолчанию (dev/этап 1а); в проде — PAYMENT_ENABLED=true. */
    private final boolean paymentEnabled;
    private final java.math.BigDecimal paymentFee;

    /** Рабочие дни ПЛ — для лимита суточного пробега при возврате однодневного ПЛ (12.5). */
    private final tj.mintrans.epd.waybill.repository.WorkDayRepository workDays;
    /** Проверять лимит суточного пробега ({@code epd.limits.daily-km-enabled}, MIGRATION.md 12.5). */
    private final boolean dailyKmEnabled;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WaybillService.class);

    public WaybillService(WaybillRepository waybills,
                          WaybillTitleRepository titles,
                          WaybillStatusEventRepository events,
                          tj.mintrans.epd.waybill.repository.WaybillPaymentRepository payments,
                          MasterDataClient masterData,
                          WaybillNumberGenerator numberGenerator,
                          BranchSerialGenerator branchSerial,
                          org.springframework.context.ApplicationEventPublisher eventPublisher,
                          TenantScope tenantScope,
                          CurrentUser currentUser,
                          WaybillInspectionRepository inspections,
                          TitleSigner titleSigner,
                          MedicalDataCrypto medicalCrypto,
                          @org.springframework.beans.factory.annotation.Value("${epd.payment.enabled:false}") boolean paymentEnabled,
                          @org.springframework.beans.factory.annotation.Value("${epd.payment.fee-somoni:10.00}") java.math.BigDecimal paymentFee,
                          tj.mintrans.epd.waybill.repository.WorkDayRepository workDays,
                          @org.springframework.beans.factory.annotation.Value("${epd.limits.daily-km-enabled:false}") boolean dailyKmEnabled) {
        this.workDays = workDays;
        this.dailyKmEnabled = dailyKmEnabled;
        this.waybills = waybills;
        this.titles = titles;
        this.events = events;
        this.payments = payments;
        this.masterData = masterData;
        this.numberGenerator = numberGenerator;
        this.branchSerial = branchSerial;
        this.eventPublisher = eventPublisher;
        this.tenantScope = tenantScope;
        this.currentUser = currentUser;
        this.inspections = inspections;
        this.titleSigner = titleSigner;
        this.medicalCrypto = medicalCrypto;
        this.paymentEnabled = paymentEnabled;
        this.paymentFee = paymentFee;
    }

    // ------------------------------------------------------------------ создание

    @Transactional
    public Waybill create(WaybillType type, String organizationRma, String vehicleRegNumber,
                          String driverRma, String secondDriverRma, String communicationType,
                          String route, String schedule, String specialMark,
                          Map<String, Object> typeData) {
        // Мультиарендность: тенант оформляет ПЛ за свою организацию либо (администратор
        // компании) за её филиал.
        if (tenantScope.isBounded() && !tenantScope.canWrite(organizationRma)) {
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
        assertTypeAllowed(org, type);
        assertDriverRested(driverRma, organizationRma, type);
        assertDriverEligible(driver, organizationRma, type);

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
     * Правка шапки черновика (сверка 25.09, A18): маршрут, график, особые отметки и поля формы (type_data).
     * В legacy лист до выпуска правился в той же форме; у нас черновик можно было только аннулировать и
     * оформить заново. Только статус DRAFT (после Т1 шапка подписана титулом). {@code null} — поле не
     * меняется, пустая строка — очистить; переданные ключи type_data дополняют/заменяют прежние. Проверки —
     * те же, что при создании (маршрут у маршрутных форм, «Самт» 2-Б, дозвол 5Б-БМ/4М-БМ и т. д.).
     */
    @Transactional
    public Waybill updateDraft(UUID id, String route, String schedule, String specialMark,
                               Map<String, Object> typeData, String actor) {
        var wb = getForUpdate(id);
        requireStatus(wb, WaybillStatus.DRAFT);
        if (route != null) wb.setRoute(route.isBlank() ? null : route.trim());
        if (schedule != null) wb.setSchedule(schedule.isBlank() ? null : schedule.trim());
        if (specialMark != null) {
            if (specialMark.length() > 500) {
                throw new UnprocessableException("Особые отметки — не более 500 знаков");
            }
            wb.setSpecialMark(specialMark.isBlank() ? null : specialMark.trim());
        }
        var merged = new java.util.LinkedHashMap<String, Object>();
        if (wb.getTypeData() != null) merged.putAll(wb.getTypeData());
        if (typeData != null) merged.putAll(typeData);
        merged.remove("secondDriverSnapshot");   // пересобирается проверкой из РМА второго водителя
        var org = wb.getOrganizationSnapshot() == null ? Map.<String, Object>of() : wb.getOrganizationSnapshot();
        validateTypeData(wb, merged, wb.getSecondDriverRma(), org);
        var saved = waybills.save(wb);
        // Только запись в истории листа: смены статуса нет, событие/уведомление не публикуется.
        events.save(WaybillStatusEvent.of(saved.getId(), WaybillStatus.DRAFT, WaybillStatus.DRAFT, actor,
                "Изменена шапка черновика"));
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
                // «Самт» обязателен (legacy Waybill2bRequest: direction_id required; в боевой базе — 100 %):
                // из направления берутся коэффициенты K нормы топлива 2-Б. Без него K молча = только износ.
                if (str(data.get("directionId")).isBlank()) {
                    throw new UnprocessableException("Укажите «Самт» — направление перевозки (directionId): от него зависят коэффициенты нормы топлива");
                }
                // Ходуди фаъолият (зоны работы, 1=Душанбе..7=Ҳисор) — как в легаси, необязательный
                // многозначный признак; если указан — каждое значение должно быть в диапазоне 1–7.
                validateWorkRegions(data.get("workRegions"));
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
                    requireTruckIntlFields(data);
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
                validateWorkRegions(data.get("workRegions"));
            }
            case WB_BUS, WB_TROLLEYBUS, WB_MINIBUS -> { // 1-АД / 1-А — маршрутные формы
                // Маршрут обязателен (legacy Waybill1adRequest/Waybill1aRequest: route_id required — выбор из
                // маршрутов компании): от него — длины, нулевые пробеги, коэффициенты, пассажирооборот.
                if (wb.getRoute() == null || wb.getRoute().isBlank()) {
                    throw new UnprocessableException("Укажите маршрут из справочника маршрутов организации");
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
            String key = str(fd.get("fieldKey"));
            Object value = custom.get(key);
            if (Boolean.TRUE.equals(fd.get("required"))) {
                if (value == null || str(value).isBlank()) {
                    String label = str(fd.get("labelRu"));
                    throw new UnprocessableException(
                            "Обязательное дополнительное поле «%s» не заполнено".formatted(label.isBlank() ? key : label));
                }
            }
            // Значение — по типу поля (число, дата, да/нет, вариант списка).
            String error = CustomFieldRules.valueError(fd, value);
            if (error != null) {
                throw new UnprocessableException(error);
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

    /**
     * Ходуди фаъолият (зоны работы 2-Б/3-С, legacy 7 именованных зон, 1=Душанбе..7=Ҳисор,
     * spec/data/dictionaries.yaml). Отдельного справочника зон в проекте намеренно нет —
     * это лёгкий числовой код, как {@code Organization.regionId}/{@code Route.regionId}.
     * Необязательное поле; если указано — каждое значение обязано быть в диапазоне 1–7.
     */
    private static void validateWorkRegions(Object workRegions) {
        if (workRegions == null) return;
        if (!(workRegions instanceof java.util.List<?> list)) {
            throw new UnprocessableException("Ходуди фаъолият (workRegions): ожидается массив чисел 1–7");
        }
        for (Object item : list) {
            int code;
            try {
                code = Integer.parseInt(String.valueOf(item).trim());
            } catch (NumberFormatException e) {
                throw new UnprocessableException("Ходуди фаъолият (workRegions): значение «%s» не число".formatted(item));
            }
            if (code < 1 || code > 7) {
                throw new UnprocessableException("Ходуди фаъолият (workRegions): значение «%d» вне диапазона 1–7".formatted(code));
            }
        }
    }

    private static void requireText(Map<String, Object> data, String field, String message) {
        if (str(data.get(field)).isBlank()) {
            throw new UnprocessableException(message);
        }
    }

    /** Множество разрешённых организации типов ПЛ из CSV {@code allowedWaybillTypes} ({@code null} — без ограничения). */
    private static java.util.Set<String> allowedTypeSet(Map<String, Object> org) {
        String csv = str(org.get("allowedWaybillTypes"));
        if (csv == null || csv.isBlank()) {
            return null;
        }
        return java.util.Arrays.stream(csv.split("[,;\\s]+"))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Право организации на тип ПЛ (аналог per-user permissions «Роҳхат»): если у организации
     * задан список {@code allowedWaybillTypes} и тип в него не входит — выписка запрещена.
     */
    void assertTypeAllowed(Map<String, Object> org, WaybillType type) {
        var allowed = allowedTypeSet(org);
        if (allowed != null && !allowed.contains(type.name())) {
            throw new UnprocessableException(
                    "Организация не имеет права выписывать путевые листы этого типа (%s)".formatted(type.legacyForm()));
        }
    }

    /** Блокирующие проверки перед выдачей (checks.yaml, подмножество этапа 1а). Package-private: переиспользуется AggregatorService. */
    /**
     * Один активный ПЛ на ТС и на водителя — прикладная проверка с понятным сообщением.
     * DRAFT намеренно не входит в OPEN_STATUSES (см. миграцию V7): черновик ещё не «действует»
     * и не блокирует создание других черновиков на то же ТС/водителя. Но ИМЕННО поэтому этот
     * же метод обязателен и в {@link #signT1} — тот самый момент, когда черновик становится
     * действующим документом: без него единственной защитой остаётся частичный уникальный
     * индекс БД (uq_active_waybill_vehicle/driver), и диспетчер видел бы вместо понятного 409
     * сырую ошибку нарушения ограничения СУБД.
     */
    private void assertNoOpenWaybill(String vehicleRegNumber, String driverRma) {
        if (!waybills.findByVehicleRegNumberAndStatusIn(vehicleRegNumber, WaybillStatus.OPEN_STATUSES).isEmpty()) {
            throw new ConflictException("На это транспортное средство уже оформлен действующий путевой лист");
        }
        if (!waybills.findByDriverRmaAndStatusIn(driverRma, WaybillStatus.OPEN_STATUSES).isEmpty()) {
            throw new ConflictException("На этого водителя уже оформлен действующий путевой лист");
        }
    }

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
        // «Қарздор» — отметка перевозчика (legacy debt = 0 в выборе водителя; сверка 25.09, F6).
        if (Boolean.TRUE.equals(driver.get("debtor"))) {
            throw new UnprocessableException("Водитель отмечен как «Қарздор» (должник) — снимите отметку в разделе «Водители»");
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
        } else {
            // Legacy (AuthenticatesUsers → Notification): истёкшая лицензия блокирует работу
            // организации любого вида; ведомственным без даты лицензии выписка не запрещается.
            var licenseTo = dateOrNull(org.get("licenseTo"));
            if (licenseTo != null && licenseTo.isBefore(today)) {
                throw new UnprocessableException("Срок лицензии организации истёк (" + licenseTo + ")");
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
        assertNoOpenWaybill(str(vehicle.get("registrationNumber")), str(driver.get("rma")));
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
    /**
     * Возрастной ценз и минимальный стаж водителя — из движка политик
     * ({@code block_minor_driver}, {@code min_driver_experience_years}, уровни
     * NATIONAL/ORGANIZATION/VEHICLE_TYPE). Оба по умолчанию выключены (false / 0):
     * так же, как {@code min_rest_hours}, пробел в настройке не блокирует легальный рейс.
     *
     * <p>Но если правило ВКЛЮЧЕНО, а данных в справочнике нет — это отказ, а не пропуск:
     * иначе требование обходится незаполненным полем, и включённое правило ничего не значит.
     * Сообщение прямо называет, чего не хватает, чтобы диспетчер понял, что чинить.</p>
     */
    private void assertDriverEligible(Map<String, Object> driver, String organizationRma, WaybillType type) {
        var policies = masterData.effectivePolicies(organizationRma, type.name());

        if (Boolean.parseBoolean(String.valueOf(policies.getOrDefault("block_minor_driver", "false")).trim())) {
            var birth = dateOrNull(driver.get("birthDate"));
            if (birth == null) {
                throw new UnprocessableException(
                        "Включён возрастной ценз, но у водителя не заполнена дата рождения");
            }
            if (birth.plusYears(18).isAfter(java.time.LocalDate.now())) {
                throw new UnprocessableException(
                        "Водитель несовершеннолетний — выпуск путевого листа запрещён");
            }
        }

        int minYears;
        try {
            minYears = Integer.parseInt(String.valueOf(
                    policies.getOrDefault("min_driver_experience_years", "0")).trim());
        } catch (NumberFormatException e) {
            minYears = 0;
        }
        if (minYears <= 0) {
            return;
        }
        Integer years = intOrNull(driver.get("experienceYears"));
        if (years == null) {
            throw new UnprocessableException(
                    "Требуется стаж не менее %d лет, но у водителя стаж не заполнен".formatted(minYears));
        }
        if (years < minYears) {
            throw new UnprocessableException(
                    "Стаж водителя %d лет — меньше требуемых %d".formatted(years, minYears));
        }
    }

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

    /**
     * Допустимые категории ВУ по типу ТС (справочник ЭПД 1..6): null — не проверяется. Подкатегории: микроавтобус
     * водит и D1, грузовой — и C1 (масса ТС в карточке не всегда известна, строже не решаем).
     */
    static java.util.Set<String> requiredLicenseCategories(Integer transportType) {
        if (transportType == null) {
            return null;
        }
        return switch (transportType) {
            case 1 -> java.util.Set.of("D");           // автобус
            case 3 -> java.util.Set.of("D", "D1");     // микроавтобус
            case 4 -> java.util.Set.of("B");           // легковой
            case 5, 6 -> java.util.Set.of("C", "C1");  // грузовой, грузовой международный
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
        var required = requiredLicenseCategories(intOrNull(vehicle.get("transportType")));
        if (required == null) {
            return;
        }
        // Текст реестра нормализуется («BCD», «ВСД», «В.С.Д», «ВВ1СС1» → множество категорий); пустое или
        // неразборчивое поле не проверяется — пробел в реестре не должен блокировать законный рейс.
        String categories = str(driver.get("licenseCategories"));
        if (!LicenseCategories.hasAny(categories, required)) {
            throw new UnprocessableException(
                    "Категория водительского удостоверения не соответствует типу ТС: требуется «%s» (в реестре: «%s»)"
                            .formatted(String.join("» или «", required), categories));
        }
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
        if (tenantScope.isBounded() && !tenantScope.canWrite(organizationRma)) {
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
        if (tenantScope.isBounded() && !tenantScope.canWrite(organizationRma)) {
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
        } else {
            var licenseTo = dateOrNull(org.get("licenseTo"));
            if (licenseTo != null && licenseTo.isBefore(today)) {
                out.add(new CheckResult("LICENSE_EXPIRED", "ERROR", "Срок лицензии организации истёк (" + licenseTo + ")"));
            }
        }
        // Предупреждение за 30 дней до окончания лицензии (legacy: «пас аз N рӯз … қатъ карда мешавад»).
        var licenseSoon = dateOrNull(org.get("licenseTo"));
        if (licenseSoon != null && !licenseSoon.isBefore(today) && licenseSoon.isBefore(today.plusDays(30))) {
            out.add(new CheckResult("LICENSE_EXPIRING", "WARN",
                    "Лицензия организации истекает " + licenseSoon + " — через " + java.time.temporal.ChronoUnit.DAYS.between(today, licenseSoon) + " дн."));
        }
        // Разрешённые организации типы ПЛ (per-org permissions, аналог «Роҳхат»).
        var allowedTypes = allowedTypeSet(org);
        if (allowedTypes != null && !allowedTypes.contains(type.name())) {
            out.add(new CheckResult("TYPE_NOT_LICENSED", "ERROR",
                    "Организация не имеет права выписывать путевые листы этого типа"));
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
            if (Boolean.TRUE.equals(driver.get("debtor"))) {
                out.add(new CheckResult("DRIVER_DEBTOR", "ERROR", "Водитель отмечен как «Қарздор» (должник)"));
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
            var required = requiredLicenseCategories(intOrNull(vehicle.get("transportType")));
            if (required != null && !LicenseCategories.hasAny(str(driver.get("licenseCategories")), required)) {
                out.add(new CheckResult("DRIVER_CATEGORY", "ERROR",
                        "Категория ВУ не соответствует типу ТС: требуется «%s» (в реестре: «%s»)"
                                .formatted(String.join("» или «", required), str(driver.get("licenseCategories")))));
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
        // Черновик становится действующим документом именно здесь — если на это ТС/водителя
        // уже выпущен другой действующий ПЛ (например, ещё один параллельный черновик того же
        // ТС был подписан раньше), явная проверка даёт понятный 409 вместо сырой ошибки
        // уникального индекса БД (uq_active_waybill_vehicle/driver, миграция V7).
        assertNoOpenWaybill(wb.getVehicleRegNumber(), wb.getDriverRma());
        var from = validFrom != null ? validFrom : OffsetDateTime.now();
        // Лимит срока действия: по умолчанию — срок типа ПЛ; политика max_validity_days (уровни
        // NATIONAL/ORGANIZATION/VEHICLE_TYPE) задаёт срок в пределах юридического максимума формы.
        // Так 3-С «30» предприятий Душанбе (legacy Waybill3c30, до 30 дней) включается политикой
        // организации max_validity_days=30, а не хардкодом списка компаний (MIGRATION.md 3.6/5.7).
        int typeCap = wb.getWaybillType().maxValidityDays();
        int effectiveCap = typeCap;
        String maxDaysRule = masterData
                .effectivePolicies(wb.getOrganizationRma(), wb.getWaybillType().name())
                .get("max_validity_days");
        if (maxDaysRule != null) {
            try {
                int policyCap = Integer.parseInt(maxDaysRule.trim());
                if (policyCap >= 1) {
                    effectiveCap = Math.min(wb.getWaybillType().legalMaxValidityDays(), policyCap);
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

    /**
     * Начало срока при Т1 (плановый выезд 1-АД, «Вақти баромад»). В legacy дата листа — день оформления
     * (у 1-А/3-С поле только для чтения, у 1-АД выбирается время выезда этого дня). Раньше API принимал
     * любую дату — лист можно было «выписать» задним числом или на месяц вперёд. Допускается от начала
     * текущих суток (выезд раньше подписи — обычная утренняя практика) до +24 ч (выписка с вечера).
     * Проверяется на входе диспетчера ({@code POST /titles/t1}); внутренние вызовы со своей датой —
     * B2B-канал КВД (дата из его заявки) и одобрение заявки водителя на день — не ограничиваются.
     */
    public static void assertValidFrom(OffsetDateTime validFrom, OffsetDateTime now) {
        if (validFrom == null) {
            return;
        }
        var zone = java.time.ZoneId.systemDefault();
        var startOfToday = now.atZoneSameInstant(zone).toLocalDate().atStartOfDay(zone).toOffsetDateTime();
        if (validFrom.isBefore(startOfToday) || validFrom.isAfter(now.plusHours(24))) {
            throw new UnprocessableException("Начало срока листа — с начала текущих суток и не позднее чем через 24 ч "
                    + "(указано %s)".formatted(validFrom.atZoneSameInstant(zone).toLocalDateTime()));
        }
    }

    /** Т2/Т6 — медицинский осмотр. */
    @Transactional
    public Waybill confirmMed(UUID id, String doctorRma, boolean passed, Map<String, Object> indicators) {
        var wb = getForUpdate(id);
        var doctor = requireEmployee(wb, doctorRma, 1, "Врач");
        // §8 QA: сертификат врача, проводящего медосмотр, не должен быть просрочен на дату осмотра.
        // Врач сопоставляется с Employee master-data по его РМА из тела запроса (requireEmployee →
        // masterData.findEmployee), поэтому certValidTo врача доступен прямо здесь. Проверка МЯГКАЯ:
        // незаполненный срок (NULL — пробел в справочнике) НЕ блокирует, ровно как медсправка
        // водителя (medCertValidTo) и страховой полис ТС (insuranceValidTo); блокирует только явно
        // истёкший сертификат. Действует и для Т2 (предрейсовый), и для Т6 (послерейсовый) осмотра.
        var doctorCertValidTo = dateOrNull(doctor.get("certValidTo"));
        if (doctorCertValidTo != null && doctorCertValidTo.isBefore(LocalDate.now())) {
            throw new UnprocessableException("Срок действия сертификата врача истёк");
        }
        if (wb.getStatus() == WaybillStatus.CREATED || wb.getStatus() == WaybillStatus.TECH_REJECTED) {
            if (titles.existsByWaybillIdAndTitleTypeAndSignerRma(id, "T2", doctorRma) && wb.isMedPassed()) {
                throw new ConflictException("Этот сотрудник уже подтвердил данный путевой лист");
            }
            addTitle(wb, "T2", doctorRma, "DOCTOR", withMedicalVerdict(indicators, passed, doctor));
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
            // Один послерейсовый осмотр на лист: повторное нажатие (двойной клик, повтор после
            // неудачного закрытия) раньше плодило второй Т6 (находка живой проверки 23.09.2026).
            if (titles.existsByWaybillIdAndTitleType(id, "T6")) {
                throw new ConflictException("Послерейсовый медосмотр (Т6) по этому путевому листу уже проведён");
            }
            addTitle(wb, "T6", doctorRma, "DOCTOR", withMedicalVerdict(indicators, passed, doctor));
            return waybills.save(wb);
        }
        throw new ConflictException("Медосмотр невозможен в статусе " + wb.getStatus());
    }

    /** Т3 — предрейсовый технический контроль (без показаний одометра). */
    @Transactional
    public Waybill confirmTech(UUID id, String mechanicRma, boolean passed, Map<String, Object> checklist) {
        return confirmTech(id, mechanicRma, passed, checklist, null);
    }

    /**
     * Т3 — предрейсовый технический контроль.
     *
     * <p>{@code odometerExit} — показания спидометра при выезде: реквизит бланка ПЛ, который
     * снимает механик у машины (он единственный видит одометр до рейса). Пишется только при
     * допуске: у отклонённого ПЛ выезда не будет.</p>
     */
    @Transactional
    public Waybill confirmTech(UUID id, String mechanicRma, boolean passed, Map<String, Object> checklist,
                               Integer odometerExit) {
        var wb = getForUpdate(id);
        if (wb.getStatus() != WaybillStatus.CREATED && wb.getStatus() != WaybillStatus.MED_REJECTED) {
            throw new ConflictException("Техконтроль невозможен в статусе " + wb.getStatus());
        }
        var mechanic = requireEmployee(wb, mechanicRma, 2, "Механик");
        if (titles.existsByWaybillIdAndTitleTypeAndSignerRma(id, "T3", mechanicRma) && wb.isTechPassed()) {
            throw new ConflictException("Этот сотрудник уже подтвердил данный путевой лист");
        }
        if (passed && odometerExit != null) {
            wb.setOdometerExit(odometerExit);
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

    /** Присвоение национального номера + журнального номера филиала и переход в READY. */
    private void assignNumberAndReady(Waybill wb, String actor) {
        Short regionId = wb.getOrganizationSnapshot() != null
                ? shortOrNull(wb.getOrganizationSnapshot().get("regionId")) : null;
        wb.setNumber(numberGenerator.next(regionId, wb.getWaybillType()));
        if (wb.getBranchSerial() == null) {
            int year = java.time.Year.now().getValue();
            wb.setBranchSerial(branchSerial.next(wb.getOrganizationRma(), year));
            wb.setBranchSerialYear((short) year);
        }
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

    /**
     * Возврат оплаты (§16 QA), бухгалтер/админ: CONFIRMED (PAID) → REFUNDED.
     * Симметрично {@link #confirmPayment}: фиксирует, кто/когда оформил возврат, причину и
     * сумму. Возврат ПОЛНЫЙ — на всю уплаченную сумму (частичный не поддерживается, см. ниже).
     *
     * <p>Статус самого путевого листа НЕ меняется: к моменту возврата ПЛ уже прошёл PAID→READY
     * (и мог уйти дальше — ISSUED/ACTIVE/COMPLETED), возврат денег не отменяет его жизненный цикл.
     * Аннулирование ПЛ — отдельное действие (CANCELLED) и здесь не выполняется.</p>
     *
     * <p>ДОПУЩЕНИЕ: фактическое движение денег — ВНЕШНЕЕ (банк-шлюз/агрегатор). Здесь
     * фиксируется только платформенное состояние возврата; реальный вызов шлюза — вне области
     * (stub-хук ниже, ср. с входящим {@code PaymentWebhookController} для подтверждения).</p>
     *
     * @param amount запрошенная сумма возврата; {@code null} = полная уплаченная сумма. Если
     *               передана и не равна уплаченной — частичный возврат, пока не поддерживается (422).
     */
    @Transactional
    public Waybill refundPayment(UUID id, String reason, java.math.BigDecimal amount, String actor) {
        if (reason == null || reason.isBlank()) {
            throw new UnprocessableException("Причина возврата обязательна");
        }
        var wb = getForUpdate(id);
        // Блокирующая загрузка платежа: сериализует одновременные возвраты (идемпотентность),
        // как и в confirmPayment — второй параллельный refund после коммита первого видит REFUNDED → 409.
        var payment = payments.findByWaybillIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Запись об оплате не найдена"));
        if (tj.mintrans.epd.waybill.domain.WaybillPayment.STATUS_REFUNDED.equals(payment.getStatus())) {
            throw new ConflictException("Оплата уже возвращена");
        }
        if (!tj.mintrans.epd.waybill.domain.WaybillPayment.STATUS_CONFIRMED.equals(payment.getStatus())) {
            throw new ConflictException("Возврат возможен только для подтверждённой (оплаченной) записи; текущий статус: "
                    + payment.getStatus());
        }
        // Полный возврат: сумма фиксируется по факту уплаты. Частичный возврат не решаем молча.
        if (amount != null && amount.compareTo(payment.getAmount()) != 0) {
            throw new UnprocessableException("Поддерживается только полный возврат на уплаченную сумму "
                    + payment.getAmount() + " " + payment.getCurrency());
        }
        // TODO(payment-gateway): инициировать фактический возврат во внешнем шлюзе/агрегаторе и
        // сохранить № возвратной транзакции в refundExternalRef. Пока — платформенный stub (no-op),
        // симметрично тому, что подтверждение денег приходит извне через PaymentWebhookController.
        payment.setRefundExternalRef(null);
        payment.setStatus(tj.mintrans.epd.waybill.domain.WaybillPayment.STATUS_REFUNDED);
        payment.setRefundAmount(payment.getAmount());
        payment.setRefundReason(reason);
        payment.setRefundedBy(actor);
        payment.setRefundedAt(OffsetDateTime.now());
        payments.save(payment);
        // Аудит: append-only журнал ПЛ (как рядом), но БЕЗ смены статуса — from == to, чтобы
        // не публиковать в Kafka ложный переход статуса (возврат денег не меняет статус ПЛ).
        events.save(tj.mintrans.epd.waybill.domain.WaybillStatusEvent.of(
                wb.getId(), wb.getStatus(), wb.getStatus(), actor,
                "Возврат оплаты %s %s (%s)".formatted(payment.getRefundAmount(), payment.getCurrency(), reason)));
        return wb;
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

    /**
     * Приём ПЛ водителем из мобильного приложения: разрешён только водителю (основному
     * или второму), на которого выписан лист, и только в статусе READY.
     */
    @Transactional
    public Waybill acceptByDriver(UUID id, String driverRma) {
        // Область видимости мобильного водителя — по РМА водителя, а не по организации:
        // токен DRIVER не несёт claim organization_rma, поэтому checkTenant неприменим.
        var wb = waybills.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Путевой лист не найден"));
        if (!driverRma.equals(wb.getDriverRma()) && !driverRma.equals(wb.getSecondDriverRma())) {
            throw new ForbiddenException("Путевой лист выписан на другого водителя");
        }
        requireStatus(wb, WaybillStatus.READY);
        transition(wb, WaybillStatus.ISSUED, driverRma, "Водитель подтвердил получение (мобильное приложение)");
        return waybills.save(wb);
    }

    /** Т4 — одометр/топливо на выезде; выезд на линию. */
    @Transactional
    public Waybill activate(UUID id, String dispatcherRma, Integer odometerExit) {
        var wb = getForUpdate(id);
        requireStatus(wb, WaybillStatus.ISSUED);
        var dispatcher = requireEmployee(wb, dispatcherRma, 3, "Диспетчер");
        Integer lastKnown = lastKnownOdometer(wb);
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
        // Без явного значения: показание механика при техконтроле (Т3) либо текущий пробег ТС из
        // справочника — большее из них. До 23.09.2026 здесь брался только снимок карточки ТС на момент
        // ВЫПИСКИ, а показание механика затиралось: если пробег в карточке с тех пор вырос (другой
        // лист, правка карточки), лист уезжал с заниженным одометром, и при закрытии справочник
        // отказывался «уменьшать» пробег — закрытие падало с 500 (находка подготовки демонстрации).
        int exit = odometerExit != null ? odometerExit : maxOrZero(wb.getOdometerExit(), lastKnown);
        wb.setOdometerExit(exit);
        addTitle(wb, "T4", dispatcherRma, "DISPATCHER", Map.of("odometerExit", exit,
                "dispatcher", str(dispatcher.get("name"))));
        transition(wb, WaybillStatus.ACTIVE, dispatcherRma, "Выезд на линию");
        return waybills.save(wb);
    }

    /**
     * Последний известный пробег ТС: больший из снимка карточки на момент выписки и ТЕКУЩЕГО
     * значения в справочнике мастер-данных (за время между выпиской и выездом его мог увеличить
     * закрытый лист или правка карточки). Справочник недоступен — только снимок.
     */
    private Integer lastKnownOdometer(Waybill wb) {
        Integer snapshot = wb.getVehicleSnapshot() != null
                ? intOrNull(wb.getVehicleSnapshot().get("odometer")) : null;
        Integer current = null;
        try {
            current = masterData.findVehicle(wb.getVehicleRegNumber())
                    .map(v -> intOrNull(v.get("odometer"))).orElse(null);
        } catch (RuntimeException e) {
            // справочник недоступен — остаёмся на снимке, выезд не блокируем
        }
        if (snapshot == null) return current;
        if (current == null) return snapshot;
        return Math.max(snapshot, current);
    }

    private static int maxOrZero(Integer a, Integer b) {
        if (a == null && b == null) return 0;
        if (a == null) return b;
        if (b == null) return a;
        return Math.max(a, b);
    }

    /** Т5 — возвращение: одометр возврата. */
    @Transactional
    public Waybill returnTrip(UUID id, String dispatcherRma, int odometerEntry) {
        return returnTrip(id, dispatcherRma, odometerEntry, null);
    }

    /**
     * Фактические показатели рейса, вносимые при возврате (для расчёта и сводных отчётов).
     * Международные формы (MIGRATION.md 3.14/3.15): {@code arrivalTime} — время прибытия в пункт
     * назначения (legacy 5Б-БМ {@code arrival_time}, отдельно от возврата в парк), {@code passengersCount}
     * — перевезено пассажиров (legacy 4-МБМ {@code number_passengers}, «Шумораи мусофирон» бланка).
     */
    public record ReturnMetrics(Double transportWork, Double trips,
                                Double conditionerHours, Integer airConditionerPercent,
                                String arrivalTime, Integer passengersCount,
                                // Лист без рабочих дней (legacy «коркард»): круги, выручка (kassa/earning),
                                // селекторы нулевого пробега «гашти ибтидоӣ» начала и конца смены.
                                Integer numberLap, java.math.BigDecimal earning,
                                String beginPathA, String beginPathB) {
        public static final ReturnMetrics EMPTY = new ReturnMetrics(null, null, null, null, null, null);

        public ReturnMetrics(Double transportWork, Double trips, Double conditionerHours, Integer airConditionerPercent) {
            this(transportWork, trips, conditionerHours, airConditionerPercent, null, null);
        }

        public ReturnMetrics(Double transportWork, Double trips, Double conditionerHours, Integer airConditionerPercent,
                             String arrivalTime, Integer passengersCount) {
            this(transportWork, trips, conditionerHours, airConditionerPercent, arrivalTime, passengersCount,
                    null, null, null, null);
        }

        boolean any() {
            return transportWork != null || trips != null || conditionerHours != null || airConditionerPercent != null
                    || arrivalTime != null || passengersCount != null;
        }

        /** Переданы показатели дня (круги / выручка / гашти ибтидоӣ) — сохраняются рабочим днём. */
        boolean hasDayData() {
            return numberLap != null || earning != null
                    || (beginPathA != null && !beginPathA.isBlank()) || (beginPathB != null && !beginPathB.isBlank());
        }
    }

    /**
     * Правила международных полей возврата (MIGRATION.md 3.14/3.15): время прибытия — только у 5Б-БМ и
     * 4-МБМ и в формате ISO-8601 (yyyy-MM-ddTHH:mm[:ss]); число пассажиров — только у 4-МБМ и не меньше 0.
     * Пустая строка времени = «не задано». Возвращает нормализованное время (или null).
     */
    static String assertIntlReturnFields(WaybillType type, ReturnMetrics m) {
        if (m == null) return null;
        boolean intl = type == WaybillType.WB_TRUCK_INTL || type == WaybillType.WB_PAX_INTL;
        String arrival = m.arrivalTime() == null || m.arrivalTime().isBlank() ? null : m.arrivalTime().trim();
        if (arrival != null) {
            if (!intl) {
                throw new UnprocessableException("Время прибытия (arrivalTime) задаётся только у форм 5Б-БМ и 4-МБМ");
            }
            try {
                arrival = java.time.LocalDateTime.parse(arrival).toString();
            } catch (java.time.format.DateTimeParseException e) {
                throw new UnprocessableException("Время прибытия (arrivalTime) должно быть в формате ГГГГ-ММ-ДДTЧЧ:ММ");
            }
        }
        if (m.passengersCount() != null) {
            if (type != WaybillType.WB_PAX_INTL) {
                throw new UnprocessableException("Число пассажиров (passengersCount) задаётся только у формы 4-МБМ");
            }
            if (m.passengersCount() < 0) {
                throw new UnprocessableException("Число пассажиров (passengersCount) не может быть отрицательным");
            }
        }
        return arrival;
    }

    /** Возврат (Т5). Для спецтехники дополнительно фиксируются моточасы возврата (motorHoursEntry). */
    @Transactional
    public Waybill returnTrip(UUID id, String dispatcherRma, int odometerEntry, Double motorHoursEntry) {
        return returnTrip(id, dispatcherRma, odometerEntry, motorHoursEntry, ReturnMetrics.EMPTY);
    }

    @Transactional
    public Waybill returnTrip(UUID id, String dispatcherRma, int odometerEntry, Double motorHoursEntry,
                              ReturnMetrics metrics) {
        var wb = getForUpdate(id);
        // Просроченный на линии лист (EXPIRED после Т4, возврат не оформлен) обрабатывается так же, как
        // действующий: в legacy «просрочен и не обработан» — фильтр списка, а не запрет обработки; иначе
        // пробег, топливо, круги и выручка рейса терялись. Нарушение срока остаётся в истории статусов.
        boolean overdue = isOverdueOnLine(wb);
        if (!overdue) {
            requireStatus(wb, WaybillStatus.ACTIVE);
        }
        var dispatcher = requireEmployee(wb, dispatcherRma, 3, "Диспетчер");
        if (wb.getOdometerExit() != null && odometerEntry < wb.getOdometerExit()) {
            throw new UnprocessableException("Одометр возврата меньше одометра выезда");
        }
        // Предел пробега за лист (legacy valid_counter_value: автобус 600, троллейбус 215, 1-А 1600,
        // 3-С 2800, 3-С «30» 12 000 км) — защита от ошибки ввода одометра возврата.
        int maxTripKm = wb.getWaybillType().maxTripKm(validityDays(wb));
        if (maxTripKm > 0 && wb.getOdometerExit() != null && odometerEntry - wb.getOdometerExit() > maxTripKm) {
            throw new UnprocessableException("Пробег %d км за лист превышает предел формы %s — %d км; проверьте одометр возврата"
                    .formatted(odometerEntry - wb.getOdometerExit(), wb.getWaybillType().legacyForm(), maxTripKm));
        }
        // Показатели листа без рабочих дней (legacy вкладка «коркард»: number_lap, earning/kassa, гашти
        // ибтидои А/Б) вносятся при возврате и сохраняются рабочим днём — так же хранит их архив
        // (один день на однодневный лист), и расчёт/отчёты читают их из одного места.
        if (metrics != null && metrics.hasDayData()) {
            if (metrics.numberLap() != null && (metrics.numberLap() < 0 || metrics.numberLap() > 99)) {
                throw new UnprocessableException("Число кругов (рейсов) — от 0 до 99");
            }
            if (metrics.earning() != null && metrics.earning().signum() < 0) {
                throw new UnprocessableException("Выручка не может быть отрицательной");
            }
            assertBeginPath(metrics.beginPathA(), true);
            assertBeginPath(metrics.beginPathB(), false);
            if (workDays.countByWaybillId(id) > 0) {
                throw new UnprocessableException("У листа есть рабочие дни — круги и выручка вносятся по дням");
            }
            var t4 = titles.findFirstByWaybillIdAndTitleTypeOrderBySignedAtDesc(id, "T4").orElse(null);
            var exitAt = t4 != null ? t4.getSignedAt() : wb.getValidFrom();
            var now = OffsetDateTime.now();
            var day = new tj.mintrans.epd.waybill.domain.WorkDay();
            day.setWaybillId(id);
            day.setWorkDate((exitAt != null ? exitAt : now).toLocalDate());
            if (exitAt != null) day.setExitTime(exitAt.toLocalTime().withNano(0));
            day.setEntryTime(now.toLocalTime().withNano(0));
            day.setOdometerExit(wb.getOdometerExit());
            day.setOdometerEntry(odometerEntry);
            // Legacy (BillNumberTrait): при нулевом пробеге 1-АД число кругов обнуляется.
            boolean zeroRun = wb.getOdometerExit() != null && odometerEntry == wb.getOdometerExit();
            day.setLaps(zeroRun && isBusForm(wb.getWaybillType()) ? Integer.valueOf(0) : metrics.numberLap());
            day.setRevenue(metrics.earning());
            day.setBeginPathA(blankToNull(metrics.beginPathA()));
            day.setBeginPathB(blankToNull(metrics.beginPathB()));
            if (metrics.conditionerHours() != null) {
                day.setConditionerHours(java.math.BigDecimal.valueOf(metrics.conditionerHours()));
            }
            workDays.save(day);
        }
        // Лимит суточного пробега (MIGRATION.md 12.5) для ПЛ без рабочих дней: пробег по шапке = один день.
        int maxDailyKm = wb.getWaybillType().maxDailyKm();
        if (dailyKmEnabled && maxDailyKm > 0 && wb.getOdometerExit() != null
                && odometerEntry - wb.getOdometerExit() > maxDailyKm
                && workDays.countByWaybillId(id) == 0) {
            throw new UnprocessableException("Пробег %d км превышает суточный лимит формы %s — %d км"
                    .formatted(odometerEntry - wb.getOdometerExit(), wb.getWaybillType().legacyForm(), maxDailyKm));
        }
        String arrivalTime = assertIntlReturnFields(wb.getWaybillType(), metrics);
        wb.setOdometerEntry(odometerEntry);
        if (metrics != null && metrics.any()) {
            var td = wb.getTypeData() != null
                    ? new java.util.LinkedHashMap<String, Object>(wb.getTypeData())
                    : new java.util.LinkedHashMap<String, Object>();
            if (metrics.transportWork() != null) td.put("transportWork", metrics.transportWork());
            if (metrics.trips() != null) td.put("trips", metrics.trips());
            if (metrics.conditionerHours() != null) td.put("conditionerHours", metrics.conditionerHours());
            if (metrics.airConditionerPercent() != null) td.put("airConditionerPercent", metrics.airConditionerPercent());
            // 5Б-БМ/4-МБМ: прибытие в пункт назначения и перевезённые пассажиры (MIGRATION.md 3.14/3.15).
            if (arrivalTime != null) td.put("arrivalTime", arrivalTime);
            if (metrics.passengersCount() != null) td.put("passengersCount", metrics.passengersCount());
            wb.setTypeData(td);
        }
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
                "distance", wb.getOdometerExit() != null ? odometerEntry - wb.getOdometerExit() : 0,
                // Ф.И.О. в данных титула — чтобы отметка возврата на бланке показывала диспетчера по
                // имени, а не РМА (Т1 так делал всегда, Т4/Т5 — нет; находка 23.09.2026).
                "dispatcher", str(dispatcher.get("name"))));
        transition(wb, WaybillStatus.RETURNED, dispatcherRma,
                overdue ? "Возвращение после истечения срока действия (нарушение срока сохранено в истории)" : "Возвращение");
        var saved = waybills.save(wb);
        // Пробег ТС в справочнике обновляется сразу при возврате (legacy BillNumberTrait: parkings.
        // indication_counter = одометр возврата при обработке), а не только при закрытии: иначе, пока
        // лист ждёт Т6, следующий уезжал со старым одометром. Отказ справочника (в карточке уже больше)
        // возврат не блокирует — повторная попытка будет при закрытии.
        if (wb.getVehicleSnapshot() != null && wb.getVehicleSnapshot().get("id") != null) {
            try {
                masterData.updateVehicleOdometer(str(wb.getVehicleSnapshot().get("id")), odometerEntry);
            } catch (RuntimeException e) {
                log.warn("Пробег ТС {} при возврате ПЛ {} не обновлён: {}", wb.getVehicleRegNumber(), wb.getId(), e.toString());
            }
        }
        return saved;
    }

    /**
     * Лист просрочен на линии: переведён в EXPIRED планировщиком после выезда (Т4), возврат (Т5) не
     * оформлен. Такой лист можно вернуть и дооформить (рабочие дни, топливо) — как в legacy.
     */
    public boolean isOverdueOnLine(Waybill wb) {
        return wb.getStatus() == WaybillStatus.EXPIRED
                && titles.existsByWaybillIdAndTitleType(wb.getId(), "T4")
                && !titles.existsByWaybillIdAndTitleType(wb.getId(), "T5");
    }

    /** Фактический срок листа в днях (validFrom … validTo), 0 — срок не задан. */
    static long validityDays(Waybill wb) {
        if (wb.getValidFrom() == null || wb.getValidTo() == null) {
            return 0;
        }
        long hours = java.time.Duration.between(wb.getValidFrom(), wb.getValidTo()).toHours();
        return Math.max(1, (hours + 23) / 24);
    }

    private static boolean isBusForm(WaybillType type) {
        return type == WaybillType.WB_BUS || type == WaybillType.WB_TROLLEYBUS;
    }

    /** Селектор нулевого пробега legacy: имя поля маршрута «begin_path_a» / «begin_path_b». */
    static void assertBeginPath(String selector, boolean first) {
        if (selector == null || selector.isBlank()) {
            return;
        }
        if (!"begin_path_a".equals(selector) && !"begin_path_b".equals(selector)) {
            throw new UnprocessableException("Гашти ибтидоӣ %s: допустимо «begin_path_a» (А) или «begin_path_b» (Б)"
                    .formatted(first ? "(начало)" : "(конец)"));
        }
    }

    /**
     * Данные накладной (приложение к 2-Б / CMR к 5Б-БМ) — стороны, груз, операции
     * погрузки-разгрузки. Заполняются диспетчером по ходу рейса, не участвуют в статусной
     * машине и не требуют титула: это описательные данные документа, а не подписываемое
     * действие. Не переданные (null) поля не затирают уже сохранённые значения.
     */
    public record ConsignmentUpdate(
            String senderName, String senderAddress,
            String receiverName, String receiverAddress,
            String forwarderName,
            Double cargoVolume, String cargoStatCode, String submittedDocuments,
            String customsOfficerName, String customsConfirmedAt,
            java.util.List<Map<String, Object>> cargoOperations,
            // Идентификаторы справочников Client (senderId/receiverId/forwarderId) и Cargo
            // (cargoId) — снимок имени остаётся в *Name полях (иммутабельность истории),
            // id хранится ДОПОЛНИТЕЛЬНО, только для прослеживаемости/отчётности, без live-join.
            String senderId, String receiverId, String forwarderId, String cargoId,
            // Наименование груза — снимок из справочника Cargo (или свободный текст), тот же
            // typeData.cargoName, что читает печатная форма (см. WaybillPrintService.model()).
            String cargoName,
            // Рамзи бор — снимок сквозного номера груза (Cargo.number, legacy cargos.number),
            // печатается в борхате (прил. 1/2, «Рамз»), MIGRATION.md 2.25.
            Long cargoNumber,
            // «Шумораи рейс» СМР (legacy cargo_waybill5bbms.reis_amount, MIGRATION.md 3.15) — число
            // ездок Z; хранится в том же typeData.trips, что вводится при возврате и идёт в расчёт.
            Integer tripsCount) {
        public ConsignmentUpdate(String senderName, String senderAddress, String receiverName, String receiverAddress,
                                 String forwarderName, Double cargoVolume, String cargoStatCode, String submittedDocuments,
                                 String customsOfficerName, String customsConfirmedAt,
                                 java.util.List<Map<String, Object>> cargoOperations,
                                 String senderId, String receiverId, String forwarderId, String cargoId,
                                 String cargoName, Long cargoNumber) {
            this(senderName, senderAddress, receiverName, receiverAddress, forwarderName, cargoVolume, cargoStatCode,
                    submittedDocuments, customsOfficerName, customsConfirmedAt, cargoOperations,
                    senderId, receiverId, forwarderId, cargoId, cargoName, cargoNumber, null);
        }
    }

    @Transactional
    public Waybill updateConsignment(UUID id, ConsignmentUpdate data) {
        var wb = getForUpdate(id);
        return applyConsignment(wb, data);
    }

    /**
     * Внешний кабинет грузоотправителя/экспедитора (MIGRATION.md 1.1/3.11): правка накладной без тенант-скоупа
     * (доступ проверяет вызывающий по клиентам пользователя); стороны/id справочников менять нельзя.
     */
    @Transactional
    public Waybill updateConsignmentByClient(UUID id, ConsignmentUpdate data) {
        var wb = waybills.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Путевой лист не найден"));
        ConsignmentUpdate safe = new ConsignmentUpdate(null, data.senderAddress(), null, data.receiverAddress(), null,
                data.cargoVolume(), data.cargoStatCode(), data.submittedDocuments(), null, null, data.cargoOperations(),
                null, null, null, null, data.cargoName(), null, data.tripsCount());
        return applyConsignment(wb, safe);
    }

    /**
     * Таможенное подтверждение СМР (legacy {@code Cargo5bbmCrudController::validatecmr}, роль customs_officer):
     * только 5Б-БМ; повтор идемпотентен («Тасдиқ шудааст»). Пишется имя/логин таможенника и момент.
     */
    @Transactional
    public Waybill confirmCustoms(UUID id, String officerName, String officerUser) {
        var wb = waybills.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Путевой лист не найден"));
        if (wb.getWaybillType() != WaybillType.WB_TRUCK_INTL) {
            throw new UnprocessableException("Таможенное подтверждение — только для СМР к 5Б-БМ");
        }
        var td = wb.getTypeData() != null
                ? new java.util.LinkedHashMap<String, Object>(wb.getTypeData())
                : new java.util.LinkedHashMap<String, Object>();
        Object already = td.get("customsConfirmedAt");
        if (already != null && !already.toString().isBlank()) {
            return wb;   // уже подтверждено — как в legacy, без ошибки
        }
        td.put("customsOfficerName", officerName == null || officerName.isBlank() ? officerUser : officerName);
        td.put("customsOfficerUser", officerUser);
        td.put("customsConfirmedAt", java.time.OffsetDateTime.now().toString());
        wb.setTypeData(td);
        return waybills.save(wb);
    }

    private Waybill applyConsignment(Waybill wb, ConsignmentUpdate data) {
        var td = wb.getTypeData() != null
                ? new java.util.LinkedHashMap<String, Object>(wb.getTypeData())
                : new java.util.LinkedHashMap<String, Object>();
        putIfPresent(td, "senderName", data.senderName());
        putIfPresent(td, "senderAddress", data.senderAddress());
        putIfPresent(td, "receiverName", data.receiverName());
        putIfPresent(td, "receiverAddress", data.receiverAddress());
        putIfPresent(td, "forwarderName", data.forwarderName());
        putIfPresent(td, "cargoVolume", data.cargoVolume());
        putIfPresent(td, "cargoStatCode", data.cargoStatCode());
        putIfPresent(td, "submittedDocuments", data.submittedDocuments());
        putIfPresent(td, "customsOfficerName", data.customsOfficerName());
        putIfPresent(td, "customsConfirmedAt", data.customsConfirmedAt());
        putIfPresent(td, "senderId", data.senderId());
        putIfPresent(td, "receiverId", data.receiverId());
        putIfPresent(td, "forwarderId", data.forwarderId());
        putIfPresent(td, "cargoId", data.cargoId());
        putIfPresent(td, "cargoName", data.cargoName());
        putIfPresent(td, "cargoNumber", data.cargoNumber());
        if (data.tripsCount() != null) {
            if (data.tripsCount() < 0) {
                throw new UnprocessableException("Число рейсов (tripsCount) не может быть отрицательным");
            }
            td.put("trips", data.tripsCount());
        }
        if (data.cargoOperations() != null) {
            td.put("cargoOperations", data.cargoOperations());
        }
        wb.setTypeData(td);
        return waybills.save(wb);
    }

    private static void putIfPresent(Map<String, Object> td, String key, Object value) {
        if (value != null) {
            td.put(key, value);
        }
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
        // exists…, а не find…: при повторном Т6 «ровно один» бросал исключение, и лист с двумя
        // послерейсовыми осмотрами навсегда оставался «возвращён» (409 «Неоднозначные данные»).
        if (requireMedPost && !titles.existsByWaybillIdAndTitleType(id, "T6")) {
            throw new ConflictException("Требуется послерейсовый медосмотр (Т6) перед закрытием путевого листа");
        }
        // Перенос одометра в мастер-данные для следующего ПЛ
        String note = "Путевой лист закрыт";
        if (wb.getOdometerEntry() != null && wb.getVehicleSnapshot() != null) {
            try {
                masterData.updateVehicleOdometer(str(wb.getVehicleSnapshot().get("id")), wb.getOdometerEntry());
            } catch (org.springframework.web.client.HttpClientErrorException.BadRequest
                     | org.springframework.web.client.HttpClientErrorException.NotFound e) {
                // Справочник отказался: пробег в карточке ТС уже БОЛЬШЕ одометра возврата (карточку
                // поправили вручную или ТС успело отъездить по другому листу) либо ТС удалено.
                // Справочник пробег не уменьшает — и не должен; но лист уже вернулся, одометр возврата
                // зафиксирован титулом Т5 и исправлен быть не может. До 23.09.2026 это было 500 и лист
                // навсегда оставался «возвращён». Теперь лист закрывается, а расхождение фиксируется
                // в истории статусов, чтобы его видели при разборе.
                note = "Путевой лист закрыт. Пробег ТС в справочнике не изменён: одометр возврата "
                        + wb.getOdometerEntry() + " меньше текущего значения в карточке ТС (или ТС нет в справочнике) — проверьте карточку";
            }
        }
        transition(wb, WaybillStatus.COMPLETED, actor, note);
        return waybills.save(wb);
    }

    /**
     * Касса 3-С: отметка «выручка сдана» (MIGRATION.md 4.7, legacy {@code Waybill3cCrudController::pay} —
     * роль employee_kassa ставит {@code employee_kassa_id}). Только формы 3-С (легковой/такси), после возврата
     * (RETURNED/COMPLETED); кассир — сотрудник организации типа 5 («касса»). Повторная отметка — идемпотентна
     * (в legacy «Пардохт шудааст», без ошибки): возвращаем ПЛ как есть.
     */
    @Transactional
    public Waybill confirmKassa(UUID id, String employeeRma, String actor) {
        var wb = getForUpdate(id);
        assertKassaAllowed(wb.getWaybillType(), wb.getStatus());
        if (wb.getKassaConfirmedAt() != null) {
            return wb;
        }
        requireEmployee(wb, employeeRma, 5, "Кассир");
        wb.setKassaEmployeeRma(employeeRma);
        wb.setKassaConfirmedAt(OffsetDateTime.now());
        // actor (логин кассира) — для аудита достаточно РМА сотрудника в самом ПЛ; отдельного события статуса нет.
        return waybills.save(wb);
    }

    /**
     * Обязательные поля 5Б-БМ (MIGRATION.md 12.10, legacy {@code kvd/StoreWaybill5bbmRequest}: load/unload
     * country+city, cargo_id, bba_number — required; client, second driver, visa — nullable): к странам
     * (проверяются выше) добавляются города погрузки/разгрузки, наименование груза и номер ББА.
     */
    static void requireTruckIntlFields(Map<String, Object> data) {
        requireText(data, "cargoName", "Укажите наименование груза (cargoName)");
        requireText(data, "loadCity", "Укажите город погрузки (loadCity)");
        requireText(data, "unloadCity", "Укажите город разгрузки (unloadCity)");
        requireText(data, "bbaNumber", "Укажите номер ББА (bbaNumber)");
    }

    /** Правило отметки кассы: только 3-С и только после возврата (RETURNED / COMPLETED). */
    static void assertKassaAllowed(WaybillType type, WaybillStatus status) {
        if (type != WaybillType.WB_CAR && type != WaybillType.WB_TAXI) {
            throw new UnprocessableException("Отметка кассы предусмотрена только для формы 3-С (легковой/такси)");
        }
        if (status != WaybillStatus.RETURNED && status != WaybillStatus.COMPLETED) {
            throw new ConflictException("Выручку можно сдать только после возврата ПЛ (текущий статус: %s)".formatted(status));
        }
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
        if (Boolean.TRUE.equals(driver.get("debtor"))) {
            throw new UnprocessableException("Новый водитель отмечен как «Қарздор» (должник)");
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

    /** Данные акта дорожной проверки: что записал инспектор при остановке. */
    public record InspectionAct(InspectionReason reasonCode, String description, String place,
                                java.math.BigDecimal lat, java.math.BigDecimal lon,
                                String protocolNumber) {
    }

    /**
     * Документ уже на линии — инспектор может встретить машину и до активации (лист выдан
     * водителю), и после возврата, пока рейс не закрыт. Проверять и блокировать разрешаем во
     * всех этих состояниях; из завершённого/аннулированного блокировать нечего.
     */
    private static final java.util.EnumSet<WaybillStatus> ON_ROAD =
            java.util.EnumSet.of(WaybillStatus.ISSUED, WaybillStatus.ACTIVE, WaybillStatus.RETURNED);

    /**
     * Проверка без нарушений: факт контроля фиксируется, статус листа не меняется.
     * Нужен и для статистики надзора, и как защита перевозчика — «этот рейс уже проверен».
     */
    @Transactional
    public WaybillInspection inspect(UUID id, InspectionAct act) {
        var wb = getForUpdate(id);
        if (!ON_ROAD.contains(wb.getStatus())) {
            throw new ConflictException(
                    "Проверка на дороге возможна только по документу на линии (текущий статус: %s)"
                            .formatted(wb.getStatus()));
        }
        return saveInspection(wb, WaybillInspection.PASSED, act);
    }

    /**
     * Блокировка при нарушении на дорожном контроле → BLOCKED (роль INSPECTOR).
     * Основание обязательно и берётся из классификатора {@link InspectionReason}: блокировка —
     * юридическое действие, её нужно уметь сопоставлять, обжаловать и снимать. Свободное
     * описание дополняет основание; для {@code OTHER} оно обязательно.
     */
    @Transactional
    public Waybill block(UUID id, InspectionAct act, String actor) {
        if (act == null || act.reasonCode() == null) {
            throw new UnprocessableException("Укажите основание блокировки из классификатора нарушений.");
        }
        if (act.reasonCode() == InspectionReason.OTHER
                && (act.description() == null || act.description().isBlank())) {
            throw new UnprocessableException("Для основания «Иное» описание нарушения обязательно.");
        }
        var wb = getForUpdate(id);
        if (!ON_ROAD.contains(wb.getStatus())) {
            throw new ConflictException(
                    "Заблокировать можно только документ на линии (текущий статус: %s)".formatted(wb.getStatus()));
        }
        var saved = saveInspection(wb, WaybillInspection.BLOCKED, act);
        transition(wb, WaybillStatus.BLOCKED, actor, blockReasonText(saved));
        return waybills.save(wb);
    }

    /** Акт проверки: личность инспектора — из токена, не из формы (подделать нельзя). */
    private WaybillInspection saveInspection(Waybill wb, String action, InspectionAct act) {
        var rec = new WaybillInspection();
        rec.setWaybillId(wb.getId());
        rec.setAction(action);
        if (act != null) {
            rec.setReasonCode(act.reasonCode());
            rec.setDescription(blankToNull(act.description()));
            rec.setPlace(blankToNull(act.place()));
            rec.setLat(act.lat());
            rec.setLon(act.lon());
            rec.setProtocolNumber(blankToNull(act.protocolNumber()));
        }
        rec.setInspectorRma(currentUser.rma().orElse("—"));
        rec.setInspectorName(currentUser.username().orElse(null));
        return inspections.save(rec);
    }

    /** Читаемое обоснование для журнала статусов: основание + место + № акта. */
    private static String blockReasonText(WaybillInspection rec) {
        var sb = new StringBuilder(rec.getReasonCode().label());
        if (rec.getDescription() != null) {
            sb.append(": ").append(rec.getDescription());
        }
        if (rec.getPlace() != null) {
            sb.append(" · место: ").append(rec.getPlace());
        }
        if (rec.getProtocolNumber() != null) {
            sb.append(" · акт № ").append(rec.getProtocolNumber());
        }
        return sb.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** История дорожных проверок путевого листа (область видимости — как у get()). */
    public java.util.List<WaybillInspection> inspections(UUID id) {
        get(id);
        return inspections.findByWaybillIdOrderByCreatedAtDesc(id);
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
     * Регламентированная расшифровка медпоказателей (ИБ-13.1.3): доступ — только
     * SYSTEM_ADMIN и DOCTOR в пределах своей организации (уже гарантирует {@link #get},
     * применяющий {@link #checkTenant}), обязательная фиксация в аудите master-data
     * ДО расшифровки (fail-closed — см. {@link MasterDataClient#recordMedicalAccess}).
     */
    public Map<String, Object> decryptMedicalIndicators(UUID id, String titleType) {
        if (!"T2".equals(titleType) && !"T6".equals(titleType)) {
            throw new UnprocessableException("Показатели доступны только для титулов Т2/Т6");
        }
        var wb = get(id); // тенант-проверка: DOCTOR — только своя организация, SYSTEM_ADMIN — любая
        // Последний осмотр: Т2 повторяется после замены водителя, Т6 — при повторном осмотре.
        var title = titles.findFirstByWaybillIdAndTitleTypeOrderBySignedAtDesc(wb.getId(), titleType)
                .orElseThrow(() -> new NotFoundException("Титул " + titleType + " не найден"));
        masterData.recordMedicalAccess(wb.getId().toString(), titleType);
        Map<String, Object> data = title.getData();
        Object enc = data == null ? null : data.get("indicatorsEnc");
        if (!(enc instanceof String encStr) || encStr.isBlank()) {
            return Map.of();
        }
        return medicalCrypto.decryptFromBase64(encStr);
    }

    /**
     * Отметка «Копия» на печатном бланке (QA §17): каждый вызов печати увеличивает счётчик;
     * первая печать — «оригинал» (без отметки), начиная со второй — «КОПИЯ». REQUIRES_NEW —
     * вызывающая сторона (рендер PDF) читает данные в readOnly-транзакции; если бы эта запись
     * присоединилась к ней, попытка записи внутри readOnly-транзакции могла бы не сохраниться.
     *
     * @return true, если это уже НЕ первая печать (бланк нужно пометить «КОПИЯ»)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean registerPrint(UUID id) {
        var wb = getForUpdate(id);
        boolean isCopy = wb.getPrintCount() > 0;
        wb.setPrintCount(wb.getPrintCount() + 1);
        waybills.save(wb);
        return isCopy;
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
     *
     * <p>Роль DRIVER дополнительно сужается до СВОИХ рейсов (основной/второй водитель) —
     * иначе водитель видел бы карточку, титулы, QR и оплату чужого рейса своей организации
     * по прямому GET /{id}, даже притом что список (GET /waybills) уже фильтрует это
     * (см. WaybillController.list()). Тот же принцип, что применяет мобильный API
     * (MobileController.ownWaybill()) — здесь распространён на веб-эндпоинты.</p>
     */
    private Waybill checkTenant(Waybill wb) {
        if (tenantScope.isBounded() && !tenantScope.contains(wb.getOrganizationRma())) {
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
        // Диспетчер подписывает Т1/Т4/Т5 и замены своим РМА (legacy: отметка — вошедший пользователь,
        // createUser/Auth::user()). Раньше карточка подставляла РМА первого диспетчера организации, и
        // при нескольких диспетчерах на бланке и в журнале стояло чужое имя. Админы и системные
        // учётки (агрегатор, B2B-канал) по-прежнему указывают подписанта явно.
        if (type == 3 && currentUser.hasRole("DISPATCHER") && !currentUser.hasRole("SYSTEM_ADMIN")
                && !currentUser.hasRole("COMPANY_ADMIN") && !currentUser.hasRole("BRANCH_ADMIN")) {
            var own = currentUser.rma().orElse(null);
            if (own != null && !own.equals(rma)) {
                throw new ForbiddenException("Диспетчер подписывает только своим РМА (" + own + ")");
            }
        }
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

    /**
     * Т2/Т6 (медосмотр): в отличие от {@link #withVerdict}, сырые показатели
     * (пульс, давление, алкотест — особая категория ПДн, ИБ-13.1.2/13.1.3) в {@code data}
     * НЕ попадают — только их зашифрованный blob под ключом {@code indicatorsEnc}
     * (см. {@link MedicalDataCrypto}). Печать/мобильный кабинет/журналы читают только
     * verdict/employeeName и никогда indicatorsEnc — раскрытие показателей инспектору
     * и другим ролям исключено уже на уровне отсутствия читаемых данных, не только контролем
     * доступа (см. ИБ-13.7.2, InspectionJournalService.META).
     */
    private Map<String, Object> withMedicalVerdict(Map<String, Object> indicators, boolean passed, Map<String, Object> employee) {
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("verdict", passed ? "ДОПУЩЕН" : "НЕ ДОПУЩЕН");
        result.put("employeeName", employee.get("name"));
        String enc = medicalCrypto.encryptToBase64(indicators);
        if (enc != null) {
            result.put("indicatorsEnc", enc);
        }
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
