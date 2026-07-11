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
import tj.mintrans.epd.waybill.web.error.ApiErrors.ConflictException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.ForbiddenException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
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
        var org = masterData.findOrganization(organizationRma)
                .orElseThrow(() -> new NotFoundException("Организация не найдена"));
        var driver = masterData.findDriver(driverRma)
                .orElseThrow(() -> new NotFoundException("Водитель не найден"));
        var vehicle = masterData.findVehicle(vehicleRegNumber)
                .orElseThrow(() -> new NotFoundException("Транспорт не найден"));

        runBlockingChecks(org, driver, vehicle);

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
            default -> { /* прочие типы — свободная схема type_data */ }
        }
        wb.setTypeData(data.isEmpty() ? null : data);
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
        var medCert = dateOrNull(driver.get("medCertValidTo"));
        if (medCert != null && medCert.isBefore(today)) {
            throw new UnprocessableException("Срок действия медицинской справки водителя истёк");
        }
        var techInspection = dateOrNull(vehicle.get("techInspectionValidTo"));
        if (techInspection == null || techInspection.isBefore(today)) {
            throw new UnprocessableException("Технический осмотр ТС отсутствует или истёк");
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

    // ------------------------------------------------------------------ титулы

    /** Т1 — выпуск: подписывает диспетчер, ПЛ переходит в CREATED. */
    @Transactional
    public Waybill signT1(UUID id, String dispatcherRma, OffsetDateTime validFrom, Integer validityDays) {
        var wb = get(id);
        requireStatus(wb, WaybillStatus.DRAFT);
        var dispatcher = requireEmployee(dispatcherRma, 3, "Диспетчер");
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
        var wb = get(id);
        var doctor = requireEmployee(doctorRma, 1, "Врач");
        if (wb.getStatus() == WaybillStatus.CREATED || wb.getStatus() == WaybillStatus.TECH_REJECTED) {
            if (titles.existsByWaybillIdAndTitleTypeAndSignerRma(id, "T2", doctorRma) && wb.isMedPassed()) {
                throw new ConflictException("This employee has already confirmed this waybill");
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
        var wb = get(id);
        if (wb.getStatus() != WaybillStatus.CREATED && wb.getStatus() != WaybillStatus.MED_REJECTED) {
            throw new ConflictException("Техконтроль невозможен в статусе " + wb.getStatus());
        }
        var mechanic = requireEmployee(mechanicRma, 2, "Механик");
        if (titles.existsByWaybillIdAndTitleTypeAndSignerRma(id, "T3", mechanicRma) && wb.isTechPassed()) {
            throw new ConflictException("This employee has already confirmed this waybill");
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
        var wb = get(id);
        requireStatus(wb, WaybillStatus.AWAITING_PAYMENT);
        var payment = payments.findByWaybillId(id)
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
        var wb = get(id);
        requireStatus(wb, WaybillStatus.READY);
        transition(wb, WaybillStatus.ISSUED, wb.getDriverRma(),
                "Водитель подтвердил получение (" + (driverConfirmation == null ? "PIN" : driverConfirmation) + ")");
        return waybills.save(wb);
    }

    /** Т4 — одометр/топливо на выезде; выезд на линию. */
    @Transactional
    public Waybill activate(UUID id, String dispatcherRma, Integer odometerExit) {
        var wb = get(id);
        requireStatus(wb, WaybillStatus.ISSUED);
        requireEmployee(dispatcherRma, 3, "Диспетчер");
        int exit = odometerExit != null ? odometerExit
                : intOrNull(wb.getVehicleSnapshot().get("odometer")) != null
                    ? intOrNull(wb.getVehicleSnapshot().get("odometer")) : 0;
        wb.setOdometerExit(exit);
        addTitle(wb, "T4", dispatcherRma, "DISPATCHER", Map.of("odometerExit", exit));
        transition(wb, WaybillStatus.ACTIVE, dispatcherRma, "Выезд на линию");
        return waybills.save(wb);
    }

    /** Т5 — возвращение: одометр возврата. */
    @Transactional
    public Waybill returnTrip(UUID id, String dispatcherRma, int odometerEntry) {
        var wb = get(id);
        requireStatus(wb, WaybillStatus.ACTIVE);
        requireEmployee(dispatcherRma, 3, "Диспетчер");
        if (wb.getOdometerExit() != null && odometerEntry < wb.getOdometerExit()) {
            throw new UnprocessableException("Одометр возврата меньше одометра выезда");
        }
        wb.setOdometerEntry(odometerEntry);
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
        var wb = get(id);
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
        var wb = get(id);
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
        var wb = get(id);
        requireStatus(wb, WaybillStatus.MED_REJECTED);
        requireEmployee(dispatcherRma, 3, "Диспетчер");
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
        if (!waybills.findByDriverRmaAndStatusIn(newDriverRma, WaybillStatus.OPEN_STATUSES).isEmpty()) {
            throw new ConflictException("На нового водителя уже оформлен действующий путевой лист");
        }
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
        var wb = get(id);
        requireStatus(wb, WaybillStatus.TECH_REJECTED);
        requireEmployee(dispatcherRma, 3, "Диспетчер");
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
        if (Integer.valueOf(1).equals(intOrNull(wb.getOrganizationSnapshot().get("typeCompany")))) {
            var controlCard = dateOrNull(vehicle.get("controlCardValidTo"));
            if (controlCard == null || controlCard.isBefore(today)) {
                throw new UnprocessableException("Контрольная карточка нового ТС отсутствует или истекла");
            }
        }
        if (!waybills.findByVehicleRegNumberAndStatusIn(newVehicleRegNumber, WaybillStatus.OPEN_STATUSES).isEmpty()) {
            throw new ConflictException("На новое ТС уже оформлен действующий путевой лист");
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
        var wb = get(id);
        requireStatus(wb, WaybillStatus.ACTIVE);
        transition(wb, WaybillStatus.BLOCKED, actor, reason);
        return waybills.save(wb);
    }

    /** Разблокировка администратором Минтранса (с обоснованием, аудит): BLOCKED → ACTIVE. */
    @Transactional
    public Waybill unblock(UUID id, String reason, String actor) {
        var wb = get(id);
        requireStatus(wb, WaybillStatus.BLOCKED);
        transition(wb, WaybillStatus.ACTIVE, actor, reason);
        return waybills.save(wb);
    }

    // ------------------------------------------------------------------ вспомогательные

    public Waybill get(UUID id) {
        var wb = waybills.findById(id).orElseThrow(() -> new NotFoundException("Путевой лист не найден"));
        // Мультиарендность: tenant-scoped пользователь (не-админ) видит и меняет только ПЛ
        // своей организации. Все мутации проходят через get(), поэтому проверка одна.
        // 404 (а не 403) — чтобы не раскрывать существование чужого документа.
        if (currentUser.isTenantScoped()
                && !currentUser.organizationRma().map(rma -> rma.equals(wb.getOrganizationRma())).orElse(false)) {
            throw new NotFoundException("Путевой лист не найден");
        }
        return wb;
    }

    private void requireStatus(Waybill wb, WaybillStatus expected) {
        if (wb.getStatus() != expected) {
            throw new ConflictException("Операция допустима только в статусе %s (текущий: %s)".formatted(expected, wb.getStatus()));
        }
    }

    private Map<String, Object> requireEmployee(String rma, int type, String roleName) {
        var employee = masterData.findEmployee(rma)
                .orElseThrow(() -> new NotFoundException(roleName + " не найден"));
        if (!Integer.valueOf(type).equals(intOrNull(employee.get("type")))) {
            throw new UnprocessableException("Сотрудник %s не имеет роли «%s»".formatted(rma, roleName));
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
        // Dev-подпись: хеш содержимого. Prod: квалифицированная ЭП (CAdES) через Crypto Service.
        title.setSignature(sha256(wb.getId() + titleType + signerRma + data));
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

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
