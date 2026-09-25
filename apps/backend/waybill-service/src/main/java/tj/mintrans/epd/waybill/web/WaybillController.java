package tj.mintrans.epd.waybill.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.waybill.config.CurrentUser;
import tj.mintrans.epd.waybill.config.TenantScope;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.domain.WaybillStatusEvent;
import tj.mintrans.epd.waybill.domain.WaybillTitle;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.repository.WaybillStatusEventRepository;
import tj.mintrans.epd.waybill.repository.WaybillTitleRepository;
import tj.mintrans.epd.waybill.calc.WaybillCalcAssembler;
import tj.mintrans.epd.waybill.service.FuelCalculationService;
import tj.mintrans.epd.waybill.service.QrTokenService;
import tj.mintrans.epd.waybill.service.WaybillService;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/waybills")
public class WaybillController {

    private final WaybillService service;
    private final WaybillRepository waybills;
    private final WaybillTitleRepository titles;
    private final WaybillStatusEventRepository events;
    private final QrTokenService qr;
    private final FuelCalculationService fuelCalculation;
    private final WaybillCalcAssembler waybillCalc;
    private final CurrentUser currentUser;
    private final TenantScope tenantScope;
    private final tj.mintrans.epd.waybill.service.WaybillRegistryService registry;
    private final tj.mintrans.epd.waybill.service.FuelBalanceService fuelBalance;

    /** Защитный лимит листинга реестра (после миграции Ф5 в waybill ~2.3 млн архивных ПЛ). */
    private static final int LIST_CAP = 1000;
    private static final String MIGRATED = "MIGRATED";

    public WaybillController(WaybillService service, WaybillRepository waybills,
                             WaybillTitleRepository titles, WaybillStatusEventRepository events,
                             QrTokenService qr, FuelCalculationService fuelCalculation,
                             WaybillCalcAssembler waybillCalc, CurrentUser currentUser,
                             TenantScope tenantScope,
                             tj.mintrans.epd.waybill.service.WaybillRegistryService registry,
                             tj.mintrans.epd.waybill.service.FuelBalanceService fuelBalance) {
        this.fuelBalance = fuelBalance;
        this.service = service;
        this.waybills = waybills;
        this.titles = titles;
        this.events = events;
        this.qr = qr;
        this.fuelCalculation = fuelCalculation;
        this.waybillCalc = waybillCalc;
        this.currentUser = currentUser;
        this.tenantScope = tenantScope;
        this.registry = registry;
    }

    // ------------------------------------------------------------- запросы

    public record CreateRequest(
            @NotNull WaybillType waybillType,
            @NotBlank @Pattern(regexp = "\\d{9,10}") String organizationRma,
            @NotBlank String vehicleRegNumber,
            @NotBlank @Pattern(regexp = "\\d{9,10}") String driverRma,
            @Pattern(regexp = "\\d{9,10}") String secondDriverRma,
            String communicationType,
            String route,
            String schedule,
            String specialMark,
            Map<String, Object> typeData) {
    }

    public record SignT1Request(
            @NotBlank @Pattern(regexp = "\\d{9,10}") String dispatcherRma,
            OffsetDateTime validFrom,
            Integer validityDays) {
    }

    public record MedRequest(
            @NotBlank @Pattern(regexp = "\\d{9,10}") String employeeRma,
            @NotNull Boolean passed,
            Map<String, Object> indicators) {
    }

    public record TechRequest(
            @NotBlank @Pattern(regexp = "\\d{9,10}") String employeeRma,
            @NotNull Boolean passed,
            Map<String, Object> checklist,
            @PositiveOrZero Integer odometerExit) {
    }

    public record IssueRequest(String driverConfirmation) {
    }

    public record ActivateRequest(
            @NotBlank @Pattern(regexp = "\\d{9,10}") String dispatcherRma,
            Integer odometerExit) {
    }

    public record ReturnRequest(
            @NotBlank @Pattern(regexp = "\\d{9,10}") String dispatcherRma,
            @NotNull Integer odometerEntry,
            Double motorHoursEntry, // моточасы возврата — только для спецтехники (иначе null)
            // Фактические показатели рейса для расчёта и сводных отчётов (все необязательны):
            Double transportWork,      // грузовая: транспортная работа P, т·км
            Double trips,              // грузовая: число ездок Z
            Double conditionerHours,   // пассажирская: часы работы кондиционера
            Integer airConditionerPercent,
            // Международные формы (MIGRATION.md 3.14/3.15): прибытие в пункт назначения
            // (5Б-БМ/4-МБМ, ISO yyyy-MM-ddTHH:mm) и перевезено пассажиров (4-МБМ).
            String arrivalTime,
            @Min(0) Integer passengersCount,
            // Лист без рабочих дней (legacy вкладка «коркард» 1-АД): круги, выручка, «гашти ибтидоӣ»
            // начала/конца смены ('begin_path_a' | 'begin_path_b') — сохраняются рабочим днём.
            @Min(0) @jakarta.validation.constraints.Max(99) Integer numberLap,
            @jakarta.validation.constraints.DecimalMin("0") BigDecimal earning,
            String beginPathA,
            String beginPathB) {
    }

    public record CloseRequest(String actor) {
    }

    public record CancelRequest(@NotBlank String reason, String actor) {
    }

    public record ReplaceDriverRequest(
            @NotBlank @Pattern(regexp = "\\d{9,10}") String newDriverRma,
            @NotBlank @Pattern(regexp = "\\d{9,10}") String dispatcherRma) {
    }

    public record ReplaceVehicleRequest(
            @NotBlank String newVehicleRegNumber,
            @NotBlank @Pattern(regexp = "\\d{9,10}") String dispatcherRma) {
    }

    /**
     * Акт дорожной проверки. {@code reasonCode} — основание из классификатора
     * {@link tj.mintrans.epd.waybill.domain.InspectionReason} (обязательно при блокировке);
     * место, координаты и № бумажного акта фиксируются вместе с ним.
     */
    public record InspectionRequest(
            tj.mintrans.epd.waybill.domain.InspectionReason reasonCode,
            String description,
            String place,
            java.math.BigDecimal lat,
            java.math.BigDecimal lon,
            String protocolNumber,
            String actor) {

        WaybillService.InspectionAct toAct() {
            return new WaybillService.InspectionAct(reasonCode, description, place, lat, lon, protocolNumber);
        }
    }

    public record UnblockRequest(@NotBlank String reason) {
    }

    public record ConfirmPaymentRequest(String method, String externalRef) {
    }

    /** Возврат оплаты (§16 QA): причина обязательна; сумма опциональна (null = полная уплаченная). */
    public record RefundRequest(@NotBlank @Size(max = 500) String reason, BigDecimal amount) {
    }

    /** Данные накладной (приложение к 2-Б / CMR к 5Б-БМ) — см. WaybillService.ConsignmentUpdate. */
    public record ConsignmentRequest(
            String senderName, String senderAddress,
            String receiverName, String receiverAddress,
            String forwarderName,
            Double cargoVolume, String cargoStatCode, String submittedDocuments,
            String customsOfficerName, String customsConfirmedAt,
            List<Map<String, Object>> cargoOperations,
            // Справочники Client (стороны)/Cargo (груз) — id для прослеживаемости, имя уже
            // снято в *Name полях выше (см. WaybillService.ConsignmentUpdate).
            String senderId, String receiverId, String forwarderId, String cargoId,
            String cargoName,
            // Рамзи бор — снимок сквозного номера груза (Cargo.number), печать борхата (2.25).
            Long cargoNumber,
            // «Шумораи рейс» СМР (legacy reis_amount, 3.15) — число ездок Z (typeData.trips).
            @Min(0) Integer tripsCount) {
    }

    // ------------------------------------------------------------- жизненный цикл

    @PostMapping
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN')")
    public ResponseEntity<Waybill> create(@Valid @RequestBody CreateRequest req) {
        var wb = service.create(req.waybillType(), req.organizationRma(), req.vehicleRegNumber(),
                req.driverRma(), req.secondDriverRma(), req.communicationType(), req.route(),
                req.schedule(), req.specialMark(), req.typeData());
        return ResponseEntity.status(HttpStatus.CREATED).body(wb);
    }

    @PostMapping("/{id}/titles/t1")
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN')")
    public Waybill signT1(@PathVariable UUID id, @Valid @RequestBody SignT1Request req) {
        return service.signT1(id, req.dispatcherRma(), req.validFrom(), req.validityDays());
    }

    @PostMapping("/{id}/confirm-med")
    @PreAuthorize("hasAnyRole('DOCTOR','SYSTEM_ADMIN')")
    public Waybill confirmMed(@PathVariable UUID id, @Valid @RequestBody MedRequest req) {
        return service.confirmMed(id, req.employeeRma(), req.passed(), req.indicators());
    }

    @PostMapping("/{id}/confirm-tech")
    @PreAuthorize("hasAnyRole('MECHANIC','SYSTEM_ADMIN')")
    public Waybill confirmTech(@PathVariable UUID id, @Valid @RequestBody TechRequest req) {
        return service.confirmTech(id, req.employeeRma(), req.passed(), req.checklist(), req.odometerExit());
    }

    @PostMapping("/{id}/issue")
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN')")
    public Waybill issue(@PathVariable UUID id, @RequestBody(required = false) IssueRequest req) {
        return service.issue(id, req == null ? null : req.driverConfirmation());
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN')")
    public Waybill activate(@PathVariable UUID id, @Valid @RequestBody ActivateRequest req) {
        return service.activate(id, req.dispatcherRma(), req.odometerExit());
    }

    @PostMapping("/{id}/return")
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN')")
    public Waybill returnTrip(@PathVariable UUID id, @Valid @RequestBody ReturnRequest req) {
        var wb = service.returnTrip(id, req.dispatcherRma(), req.odometerEntry(), req.motorHoursEntry(),
                new WaybillService.ReturnMetrics(req.transportWork(), req.trips(),
                        req.conditionerHours(), req.airConditionerPercent(),
                        req.arrivalTime(), req.passengersCount(),
                        req.numberLap(), req.earning(), req.beginPathA(), req.beginPathB()));
        // «Бақияи пас аз даромад» — теперь известен пробег, остаток после возврата пересчитывается.
        fuelBalance.recompute(id);
        return wb;
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN')")
    public Waybill close(@PathVariable UUID id, @RequestBody(required = false) CloseRequest req) {
        var wb = service.close(id, req == null ? "system" : req.actor());
        fuelBalance.recompute(id);
        return wb;
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN')")
    public Waybill cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest req) {
        return service.cancel(id, req.reason(), req.actor());
    }

    /** Касса 3-С (MIGRATION.md 4.7): РМА сотрудника-кассира (тип 5) организации ПЛ. */
    public record KassaRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "\\d{9,10}", message = "РМА кассира должен содержать 9–10 цифр")
            String employeeRma,
            String actor) {
    }

    /**
     * Отметка кассы «выручка сдана» по ПЛ 3-С (legacy {@code pay} кассира, MIGRATION.md 4.7).
     * Роль ACCOUNTANT (бухгалтерия/касса перевозчика) и SYSTEM_ADMIN; повторная отметка идемпотентна.
     */
    @PostMapping("/{id}/kassa")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','SYSTEM_ADMIN')")
    public Waybill confirmKassa(@PathVariable UUID id, @Valid @RequestBody KassaRequest req) {
        return service.confirmKassa(id, req.employeeRma(), req.actor());
    }

    /**
     * Данные накладной (приложение к 2-Б / CMR к 5Б-БМ): стороны, груз, операции
     * погрузки-разгрузки — используются печатными формами {@code print-attachment.pdf}/
     * {@code print-cmr.pdf}. Не титул и не переход статуса — описательные данные документа.
     */
    @PostMapping("/{id}/consignment")
    @PreAuthorize("hasAnyRole('DISPATCHER','COMPANY_ADMIN','SYSTEM_ADMIN')")
    public Waybill updateConsignment(@PathVariable UUID id, @RequestBody ConsignmentRequest req) {
        return service.updateConsignment(id, new WaybillService.ConsignmentUpdate(
                req.senderName(), req.senderAddress(), req.receiverName(), req.receiverAddress(),
                req.forwarderName(), req.cargoVolume(), req.cargoStatCode(), req.submittedDocuments(),
                req.customsOfficerName(), req.customsConfirmedAt(), req.cargoOperations(),
                req.senderId(), req.receiverId(), req.forwarderId(), req.cargoId(), req.cargoName(),
                req.cargoNumber(), req.tripsCount()));
    }

    /** Замена водителя после недопуска (MED_REJECTED → CREATED, титул CORRECTION). */
    @PostMapping("/{id}/replace-driver")
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN')")
    public Waybill replaceDriver(@PathVariable UUID id, @Valid @RequestBody ReplaceDriverRequest req) {
        return service.replaceDriver(id, req.newDriverRma(), req.dispatcherRma());
    }

    /** Замена ТС после отклонения техконтролем (TECH_REJECTED → CREATED, титул CORRECTION). */
    @PostMapping("/{id}/replace-vehicle")
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN')")
    public Waybill replaceVehicle(@PathVariable UUID id, @Valid @RequestBody ReplaceVehicleRequest req) {
        return service.replaceVehicle(id, req.newVehicleRegNumber(), req.dispatcherRma());
    }

    /**
     * Блокировка инспектором при нарушении на дорожном контроле (документ на линии → BLOCKED).
     * Основание обязательно и берётся из классификатора — свободного текста недостаточно:
     * блокировка юридически значима, её обжалуют и снимает только Минтранс.
     */
    @PostMapping("/{id}/block")
    @PreAuthorize("hasAnyRole('INSPECTOR','SYSTEM_ADMIN')")
    public Waybill block(@PathVariable UUID id, @Valid @RequestBody InspectionRequest req) {
        return service.block(id, req.toAct(), req.actor() == null ? "inspector" : req.actor());
    }

    /** Проверка на дороге без нарушений: фиксируем факт контроля, статус листа не меняем. */
    @PostMapping("/{id}/inspection")
    @PreAuthorize("hasAnyRole('INSPECTOR','SYSTEM_ADMIN')")
    public tj.mintrans.epd.waybill.domain.WaybillInspection inspect(
            @PathVariable UUID id, @RequestBody(required = false) InspectionRequest req) {
        return service.inspect(id, req == null ? null : req.toAct());
    }

    /** История дорожных проверок путевого листа (кто, когда, где, с каким результатом). */
    @GetMapping("/{id}/inspections")
    public List<tj.mintrans.epd.waybill.domain.WaybillInspection> inspections(@PathVariable UUID id) {
        return service.inspections(id);
    }

    /** Справочник оснований блокировки — для выпадающего списка в кабинете инспектора. */
    @GetMapping("/inspection-reasons")
    public List<Map<String, String>> inspectionReasons() {
        return java.util.Arrays.stream(tj.mintrans.epd.waybill.domain.InspectionReason.values())
                .map(r -> Map.of("code", r.name(), "label", r.label()))
                .toList();
    }

    /** Разблокировка администратором Минтранса с обоснованием (BLOCKED → ACTIVE). */
    @PostMapping("/{id}/unblock")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public Waybill unblock(@PathVariable UUID id, @Valid @RequestBody UnblockRequest req) {
        return service.unblock(id, req.reason(), currentUser.username().orElse("mintrans-admin"));
    }

    // ------------------------------------------------------------- оплата

    /** Карточка оплаты (сумма, статус, реквизиты подтверждения). */
    @GetMapping("/{id}/payment")
    @PreAuthorize(READ_ROLES)
    public tj.mintrans.epd.waybill.domain.WaybillPayment payment(@PathVariable UUID id) {
        return service.getPayment(id);
    }

    /**
     * Подтверждение оплаты бухгалтером/админом: AWAITING_PAYMENT → PAID → READY (номер + QR).
     * Платёжный шлюз (webhook) — отдельный {@code PaymentWebhookController}, тот же сервисный
     * метод, свой актор ("payment-gateway"). Актор здесь — ТОЛЬКО из JWT (preferred_username),
     * не из тела запроса: иначе бухгалтер мог указать в теле произвольную строку вместо своего
     * реального логина, и запись «кто подтвердил оплату» в аудите была бы недостоверна (в
     * отличие от Т1/Т2/Т3, где подписант дополнительно сверяется со штатом организации).
     */
    @PostMapping("/{id}/confirm-payment")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','COMPANY_ADMIN','SYSTEM_ADMIN')")
    public Waybill confirmPayment(@PathVariable UUID id, @RequestBody(required = false) ConfirmPaymentRequest req) {
        return service.confirmPayment(id,
                req == null ? null : req.method(),
                req == null ? null : req.externalRef(),
                currentUser.username().orElse("accountant"));
    }

    /**
     * Возврат оплаты бухгалтером/админом (§16 QA): CONFIRMED (PAID) → REFUNDED. Возможен только
     * для подтверждённой оплаты; повторный возврат уже возвращённой → 409. Причина обязательна,
     * сумма опциональна (по умолчанию — полная уплаченная; частичный возврат пока → 422).
     * Актор — ТОЛЬКО из JWT (preferred_username), не из тела, как и в confirm-payment: запись
     * «кто оформил возврат» в аудите должна быть достоверна. Реальное движение денег — внешнее
     * (банк-шлюз/агрегатор); здесь фиксируется платформенное состояние возврата.
     */
    @PostMapping("/{id}/refund")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','COMPANY_ADMIN','SYSTEM_ADMIN')")
    public Waybill refund(@PathVariable UUID id, @Valid @RequestBody RefundRequest req) {
        return service.refundPayment(id, req.reason(), req.amount(),
                currentUser.username().orElse("accountant"));
    }

    // ------------------------------------------------------------- пригодность (preflight)

    /** Доступные типы ПЛ для организации по её лицензии/виду субъекта (для шага выбора типа). */
    @GetMapping("/available-types")
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN')")
    public List<WaybillService.TypeAvailability> availableTypes(@RequestParam String organizationRma) {
        return service.availableTypes(organizationRma);
    }

    /** Пригодность (тип+организация+ТС+водитель): диспетчер видит «Доступен/Недоступно + причина» до создания. */
    @GetMapping("/preflight")
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN')")
    public WaybillService.EligibilityResult preflight(
            @RequestParam WaybillType type,
            @RequestParam String organizationRma,
            @RequestParam(required = false) String vehicleRegNumber,
            @RequestParam(required = false) String driverRma) {
        return service.preflight(type, organizationRma, vehicleRegNumber, driverRma);
    }

    // ------------------------------------------------------------- чтение
    //
    // ВАЖНО: явно исключаем API_INTEGRATOR из ролей ниже (в отличие от общей политики
    // CurrentUser.isPlatformAdmin(), где у него безграничная область — это нужно
    // ТОЛЬКО для внутреннего переиспользования WaybillService.get() сервисом
    // AggregatorService при вызовах через /api/v1/aggregator/**, не для прямого HTTP-доступа
    // к общим read-эндпоинтам). Без этого ограничения client-credentials токен агрегатора
    // (ЧУРА/НЕРУ) получал бы доступ на чтение ко ВСЕЙ базе путевых листов страны — реальная
    // находка приёмочного тестирования 2026-09-04 (см. spec/notes/05).
    private static final String READ_ROLES = "hasAnyRole('DISPATCHER','DOCTOR','MECHANIC','ACCOUNTANT',"
            + "'COMPANY_ADMIN','BRANCH_ADMIN','DRIVER','SYSTEM_ADMIN','MINTRANS_ANALYST','INSPECTOR')";

    @GetMapping("/{id}")
    @PreAuthorize(READ_ROLES)
    public Waybill get(@PathVariable UUID id) {
        return service.get(id);
    }

    /**
     * Серверная пагинация реестра ПЛ (MIGRATION.md 8.4, legacy DataTables server-side): фильтры в SQL
     * (в т.ч. по JSON-полям typeData/снимков), страница + общее число. Область — как у {@link #list}:
     * тенант — своя организация с филиалами, водитель — только свои ПЛ, платформенные роли — все
     * (с необязательным {@code organizationRma}). Архив (source=MIGRATED) — только при {@code archived=true}.
     */
    @GetMapping("/page")
    @PreAuthorize(READ_ROLES)
    public tj.mintrans.epd.waybill.service.WaybillRegistryService.PageResult page(
            @RequestParam(required = false) String organizationRma,
            @RequestParam(required = false) WaybillStatus status,
            @RequestParam(required = false) WaybillType type,
            @RequestParam(required = false) String vehicle,
            @RequestParam(required = false) String driver,
            @RequestParam(required = false) String svc,
            @RequestParam(required = false) String docKind,
            @RequestParam(required = false) String client,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "false") boolean archived,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        var filter = new tj.mintrans.epd.waybill.service.WaybillRegistryService.Filter(
                organizationRma, status, type, vehicle, driver, svc, docKind, client, from, to, q, archived);
        return registry.page(filter, page, size);
    }

    /** Число ПЛ по статусам в области вызывающего без архива — карточки-счётчики реестра (8.4). */
    @GetMapping("/status-counts")
    @PreAuthorize(READ_ROLES)
    public Map<String, Long> statusCounts(@RequestParam(required = false) String organizationRma) {
        return registry.statusCounts(organizationRma);
    }

    @GetMapping
    @PreAuthorize(READ_ROLES)
    public List<Waybill> list(@RequestParam(required = false) String organizationRma,
                              @RequestParam(required = false) WaybillStatus status,
                              @RequestParam(required = false) String number,
                              @RequestParam(required = false, defaultValue = "false") boolean archived) {
        // Дефолтный реестр ИСКЛЮЧАЕТ архив (source='MIGRATED', ~2.3 млн историч. ПЛ из
        // миграции Ф5) и ОГРАНИЧЕН LIST_CAP — иначе findAll вернул бы миллионы строк (OOM).
        // archived=true — явный доступ к архиву (тоже с лимитом, организационно-ограниченный).
        // Полноценная серверная пагинация — отдельная задача.
        var cap = PageRequest.of(0, LIST_CAP);
        // Мультиарендность: тенант видит свою организацию (администратор компании — и все
        // её филиалы); пришедший organizationRma игнорируется, область берётся из токена.
        if (tenantScope.isBounded()) {
            var scoped = tenantScope.rmas();
            if (scoped.isEmpty() || scoped.contains("__none__")) {
                return List.of();
            }
            // Водитель (роль DRIVER) видит только СВОИ путевые листы (свои рейсы как основной
            // или второй водитель), а не всей организации — иначе он видел бы чужие рейсы и QR.
            final String driverRma = currentUser.hasRole("DRIVER") ? currentUser.rma().orElse("") : null;
            if (number != null) {
                return waybills.findByNumber(number)
                        .filter(wb -> scoped.contains(wb.getOrganizationRma()))
                        .filter(wb -> driverRma == null || driverRma.equals(wb.getDriverRma()) || driverRma.equals(wb.getSecondDriverRma()))
                        .map(List::of).orElseGet(List::of);
            }
            List<Waybill> result;
            if (archived) {
                result = waybills.findByOrganizationRmaInOrderByCreatedAtDesc(scoped, cap);
            } else if (status != null) {
                result = waybills.findByOrganizationRmaInAndStatusAndSourceNotOrderByCreatedAtDesc(scoped, status, MIGRATED, cap);
            } else {
                result = waybills.findByOrganizationRmaInAndSourceNotOrderByCreatedAtDesc(scoped, MIGRATED, cap);
            }
            if (driverRma != null) {
                result = result.stream()
                        .filter(wb -> driverRma.equals(wb.getDriverRma()) || driverRma.equals(wb.getSecondDriverRma()))
                        .toList();
            }
            if (archived && status != null) {
                final WaybillStatus st = status;
                result = result.stream().filter(wb -> wb.getStatus() == st).toList();
            }
            return result;
        }
        if (number != null) {
            return waybills.findByNumber(number).map(List::of).orElseGet(List::of);
        }
        if (organizationRma != null) {
            return archived
                    ? waybills.findByOrganizationRmaOrderByCreatedAtDesc(organizationRma, cap)
                    : waybills.findByOrganizationRmaAndSourceNotOrderByCreatedAtDesc(organizationRma, MIGRATED, cap);
        }
        if (status != null) {
            return archived
                    ? waybills.findByStatusOrderByCreatedAtDesc(status, cap)
                    : waybills.findByStatusAndSourceNotOrderByCreatedAtDesc(status, MIGRATED, cap);
        }
        return archived
                ? waybills.findAll(cap).getContent()
                : waybills.findBySourceNotOrderByCreatedAtDesc(MIGRATED, cap);
    }

    @GetMapping("/{id}/titles")
    @PreAuthorize(READ_ROLES)
    public List<WaybillTitle> titles(@PathVariable UUID id) {
        service.get(id);
        return titles.findByWaybillIdOrderBySignedAt(id);
    }

    /**
     * Регламентированная расшифровка медпоказателей осмотра (ИБ-13.1.3) — отдельно от
     * {@link #titles}, который отдаёт только зашифрованный blob. Доступ уже гораздо у́же
     * READ_ROLES: медработник своей организации либо администратор платформы, и только
     * с фиксацией факта доступа в аудите (см. WaybillService.decryptMedicalIndicators).
     */
    @GetMapping("/{id}/titles/{titleType}/indicators")
    @PreAuthorize("hasAnyRole('DOCTOR','SYSTEM_ADMIN')")
    public Map<String, Object> medicalIndicators(@PathVariable UUID id, @PathVariable String titleType) {
        return service.decryptMedicalIndicators(id, titleType);
    }

    @GetMapping("/{id}/status-history")
    @PreAuthorize(READ_ROLES)
    public List<WaybillStatusEvent> statusHistory(@PathVariable UUID id) {
        service.get(id);
        return events.findByWaybillIdOrderByCreatedAt(id);
    }

    /** Нормативный расход топлива и стоимость рейса (доступно после возврата, Т5). */
    @GetMapping("/{id}/fuel-calculation")
    @PreAuthorize(READ_ROLES)
    public FuelCalculationService.FuelCalculation fuelCalculation(@PathVariable UUID id) {
        return fuelCalculation.calculate(service.get(id));
    }

    /**
     * Полный расчёт путевого листа движком «Роҳхат» (пакет calc): сводный коэффициент,
     * нормативный расход по видам топлива, остатки, заработок водителя, пассажирские
     * показатели / грузовые формулы, тариф маршрута.
     *
     * <p>Величины, которых нет в модели e-Waybill (кондиционер, транспортная работа P,
     * число ездок Z, доля дохода компании), передаются в теле-дополнении (всё необязательно).</p>
     */
    @PostMapping("/{id}/calculation")
    @PreAuthorize("hasAnyRole('DISPATCHER','COMPANY_ADMIN','ACCOUNTANT','SYSTEM_ADMIN')")
    public WaybillCalcAssembler.View calculation(
            @PathVariable UUID id,
            @RequestBody(required = false) WaybillCalcAssembler.Supplement supplement) {
        return waybillCalc.calculate(service.get(id), supplement);
    }

    /** Подписанная QR-нагрузка (JWS) — её кодирует в QR мобильное приложение водителя. */
    @GetMapping("/{id}/qr")
    @PreAuthorize(READ_ROLES)
    public Map<String, String> qr(@PathVariable UUID id) {
        var wb = service.get(id);
        if (wb.getNumber() == null) {
            throw new tj.mintrans.epd.waybill.web.error.ApiErrors.ConflictException(
                    "QR доступен после присвоения номера (статус READY и далее)");
        }
        return Map.of("jws", qr.sign(wb));
    }
}
