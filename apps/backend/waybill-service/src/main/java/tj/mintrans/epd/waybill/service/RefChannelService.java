package tj.mintrans.epd.waybill.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.client.MasterDataClient;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.domain.WorkDay;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.service.RefChannelRules.Form;
import tj.mintrans.epd.waybill.web.error.ApiErrors.ConflictException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static tj.mintrans.epd.waybill.service.RefChannelRules.date;
import static tj.mintrans.epd.waybill.service.RefChannelRules.dateTime;
import static tj.mintrans.epd.waybill.service.RefChannelRules.has;
import static tj.mintrans.epd.waybill.service.RefChannelRules.intVal;
import static tj.mintrans.epd.waybill.service.RefChannelRules.listOfMaps;
import static tj.mintrans.epd.waybill.service.RefChannelRules.num;
import static tj.mintrans.epd.waybill.service.RefChannelRules.str;
import static tj.mintrans.epd.waybill.service.RefChannelRules.time;

/**
 * B2B-канал перевозчиков {@code /api/v1/ref} (MIGRATION.md 9.8 / 9.2 — legacy {@code ref/*}, {@code company.jwt:1},
 * контроллеры {@code CompanyApi/Waybill*Controller}, {@code WaybillConfirmController}, {@code DataController::remain_fuel}):
 * система перевозчика создаёт/закрывает ПЛ шести форм, подтверждает врача/механика по РМА, запрашивает остаток
 * топлива. Поля запросов и ответов — snake_case legacy. ПЛ канала — {@code source=B2B}: как и агрегаторские,
 * оплата с них не требуется (в legacy платной выдачи не было — MIGRATION.md Вопрос 24).
 */
@Service
public class RefChannelService {

    public static final String SOURCE = "B2B";
    static final String ACTOR = "b2b";
    private static final int MAX_PER_PAGE = 200;

    /** Ответ списка в форме Laravel-пагинатора legacy ({@code response()->json($paginator)}). */
    public record PageResult(@JsonProperty("current_page") int currentPage,
                             List<View> data,
                             @JsonProperty("per_page") int perPage,
                             long total,
                             @JsonProperty("last_page") int lastPage) {
    }

    /** Представление ПЛ для канала — имена полей как в legacy-ответах ({@code selected_columns} + relationships). */
    public record View(UUID id,
                       String number,
                       String status,
                       @JsonProperty("waybill_type") String waybillType,
                       @JsonProperty("waybill_type_code") Integer waybillTypeCode,
                       @JsonProperty("exit_date") OffsetDateTime exitDate,
                       @JsonProperty("entry_date") OffsetDateTime entryDate,
                       @JsonProperty("indication_counter_exit") Integer indicationCounterExit,
                       @JsonProperty("indication_counter_entry") Integer indicationCounterEntry,
                       String route,
                       String schedule,
                       @JsonProperty("special_mark") String specialMark,
                       @JsonProperty("doctor_confirmed") boolean doctorConfirmed,
                       @JsonProperty("mechanic_confirmed") boolean mechanicConfirmed,
                       Map<String, Object> company,
                       Map<String, Object> parking,
                       Map<String, Object> timesheet,
                       @JsonProperty("second_driver_rma") String secondDriverRma,
                       @JsonProperty("type_data") Map<String, Object> typeData,
                       @JsonProperty("created_at") OffsetDateTime createdAt) {
    }

    private final WaybillRepository waybills;
    private final WaybillService waybillService;
    private final WorkDayService workDayService;
    private final MasterDataClient masterData;
    private final FuelPrefillService fuelPrefill;

    public RefChannelService(WaybillRepository waybills, WaybillService waybillService, WorkDayService workDayService,
                             MasterDataClient masterData, FuelPrefillService fuelPrefill) {
        this.waybills = waybills;
        this.waybillService = waybillService;
        this.workDayService = workDayService;
        this.masterData = masterData;
        this.fuelPrefill = fuelPrefill;
    }

    // ------------------------------------------------------------------ store

    @Transactional
    public Waybill create(Form form, Map<String, Object> body) {
        RefChannelRules.validateStore(form, body);
        Map<String, Object> org = requireOrganization(body);
        String reg = str(body, "transport_registration_number").toUpperCase();
        Map<String, Object> vehicle = masterData.findVehicle(reg)
                .filter(v -> sameOrg(org, v)).orElseThrow(() -> new NotFoundException("Transport not found"));
        String driverKey = form == Form.WAYBILL5BBM ? "first_driver_rma" : "driver_rma";
        String driverRma = str(body, driverKey);
        masterData.findDriver(driverRma).filter(d -> sameOrg(org, d))
                .orElseThrow(() -> new NotFoundException("Driver not found"));
        String secondDriver = form == Form.WAYBILL5BBM && has(body, "second_driver_rma") ? str(body, "second_driver_rma") : null;
        Map<String, Object> employee = null;
        if (has(body, "employee_rma")) {
            employee = masterData.findEmployee(str(body, "employee_rma")).filter(e -> sameOrg(org, e))
                    .orElseThrow(() -> new NotFoundException("Employee not found"));
        }
        String route = resolveRoute(body);
        String communication = form == Form.WAYBILL5BBM ? "INTERNATIONAL" : null;
        Map<String, Object> typeData = RefChannelRules.typeData(form, body);

        Waybill wb = waybillService.create(form.primary(), str(body, "organization_rma"), s(vehicle.get("registrationNumber")).isBlank() ? reg : s(vehicle.get("registrationNumber")),
                driverRma, secondDriver, communication, route, nullIfBlank(str(body, "schedule")),
                nullIfBlank(str(body, "special_mark")), typeData);
        wb.setSource(SOURCE);
        List<Map<String, Object>> workDays = listOfMaps(body, "work_days");
        if (!workDays.isEmpty()) {
            Map<String, Object> td = new LinkedHashMap<>(wb.getTypeData() == null ? Map.of() : wb.getTypeData());
            td.put("b2bWorkDays", workDays);   // материализуются в рабочие дни при закрытии (ПЛ должен быть ACTIVE)
            wb.setTypeData(td);
        }
        wb = waybills.save(wb);

        // Топливо «на выезд» (1-АД/1-АДЕ/5Б-БМ: fuels на верхнем уровне) — записи топлива ПЛ.
        for (Map<String, Object> f : listOfMaps(body, "fuels")) {
            addFuel(wb.getId(), null, f);
        }

        // Т1: диспетчер организации (employee_rma type 3) подписывает; иначе — как у агрегатора — заявка
        // принимается без Т1 (в legacy dispatcher_id обязателен только для 1-АД/1-АДЕ).
        OffsetDateTime validFrom = exitDateTime(form, body);
        if (employee != null && Integer.valueOf(3).equals(intOrNull(employee.get("type")))) {
            wb = waybillService.signT1(wb.getId(), str(body, "employee_rma"), validFrom, null);
        } else {
            wb.setValidFrom(validFrom);
            wb.setValidTo(validFrom.plusDays(form.primary().maxValidityDays()));
            waybillService.transition(wb, WaybillStatus.CREATED, ACTOR, "Заявка перевозчика (B2B) принята");
            wb = waybills.save(wb);
        }
        return wb;
    }

    // ------------------------------------------------------------------ index / show

    @Transactional(readOnly = true)
    public PageResult list(Form form, Map<String, String> params) {
        Map<String, Object> p = new LinkedHashMap<>(params);
        Map<String, Object> org = has(p, "organization_rma") ? requireOrganization(p) : null;
        String driverRma = nullIfBlank(str(p, "driver_rma"));
        String reg = nullIfBlank(str(p, "transport_registration_number"));
        String regUpper = reg == null ? null : reg.toUpperCase();
        Map<String, Object> employee = has(p, "employee_rma")
                ? masterData.findEmployee(str(p, "employee_rma")).orElseThrow(() -> new NotFoundException("Employee not found"))
                : null;
        Integer employeeType = employee == null ? null : intOrNull(employee.get("type"));
        LocalDate from = date(p, "date_from");
        LocalDate to = date(p, "date_to");
        if (from != null && to != null && to.isBefore(from)) {
            throw new UnprocessableException("date_to должна быть не раньше date_from.");
        }
        int perPage = Math.min(Math.max(1, intVal(p, "per_page") == null ? 10 : intVal(p, "per_page")), MAX_PER_PAGE);
        int page = Math.max(1, intVal(p, "page") == null ? 1 : intVal(p, "page"));
        boolean byExitDate = form == Form.WAYBILL3C || form == Form.WAYBILL5BBM;

        Specification<Waybill> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(root.get("waybillType").in(form.types()));
            if (org != null) {
                ps.add(cb.equal(root.get("organizationRma"), s(org.get("rma"))));
            }
            if (driverRma != null) {
                ps.add(form == Form.WAYBILL5BBM
                        ? cb.or(cb.equal(root.get("driverRma"), driverRma), cb.equal(root.get("secondDriverRma"), driverRma))
                        : cb.equal(root.get("driverRma"), driverRma));
            }
            if (regUpper != null) {
                ps.add(cb.equal(root.get("vehicleRegNumber"), regUpper));
            }
            if (employeeType != null && employeeType == 1) {
                ps.add(cb.isFalse(root.get("medPassed")));
            } else if (employeeType != null && employeeType == 2) {
                ps.add(cb.isFalse(root.get("techPassed")));
            }
            String dateField = byExitDate ? "validFrom" : "createdAt";
            if (from != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get(dateField), WaybillPeriodScan.lower(from)));
            }
            if (to != null) {
                ps.add(cb.lessThan(root.get(dateField), WaybillPeriodScan.upper(to)));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
        Page<Waybill> pg = waybills.findAll(spec, PageRequest.of(page - 1, perPage, Sort.by(Sort.Direction.DESC, "createdAt")));
        return new PageResult(page, pg.getContent().stream().map(RefChannelService::view).toList(), perPage,
                pg.getTotalElements(), Math.max(1, pg.getTotalPages()));
    }

    @Transactional(readOnly = true)
    public Waybill get(Form form, UUID id) {
        Waybill wb = waybills.findById(id).orElseThrow(() -> new NotFoundException("Waybill not found"));
        if (!form.types().contains(wb.getWaybillType())) {
            throw new NotFoundException("Waybill not found");
        }
        return wb;
    }

    // ------------------------------------------------------------------ update (в т.ч. закрытие)

    /**
     * Legacy {@code update}: правка полей формы; при {@code indication_counter_entry} — закрытие ПЛ (возврат).
     * В e-Waybill возврат идёт через жизненный цикл: READY → выдача → выезд → возврат; неподтверждённый ПЛ закрыть
     * нельзя (409). Смена ТС/водителя после оформления не поддерживается (снимки мастер-данных) — 409.
     */
    @Transactional
    public Waybill update(Form form, UUID id, Map<String, Object> body) {
        RefChannelRules.validateUpdate(form, body);
        Waybill wb = get(form, id);
        if (has(body, "organization_rma")) {
            Map<String, Object> org = requireOrganization(body);
            if (!s(org.get("rma")).equals(wb.getOrganizationRma())) {
                throw new NotFoundException("Waybill not found or does not belong to this organization");
            }
        }
        if (has(body, "transport_registration_number")
                && !str(body, "transport_registration_number").equalsIgnoreCase(wb.getVehicleRegNumber())) {
            throw new ConflictException("Смена ТС после оформления ПЛ не поддерживается — оформите новый ПЛ");
        }
        if (has(body, "driver_rma") && !str(body, "driver_rma").equals(wb.getDriverRma())) {
            throw new ConflictException("Смена водителя после оформления ПЛ не поддерживается — используйте замену водителя диспетчером");
        }
        if (wb.getStatus().isTerminal()) {
            throw new ConflictException("Waybill is closed (%s)".formatted(wb.getStatus()));
        }
        if (has(body, "schedule")) {
            wb.setSchedule(str(body, "schedule"));
        }
        if (has(body, "route_id") || has(body, "route")) {
            wb.setRoute(resolveRoute(body));
        }
        if (body.containsKey("special_mark")) {
            wb.setSpecialMark(nullIfBlank(str(body, "special_mark")));
        }
        Map<String, Object> td = new LinkedHashMap<>(wb.getTypeData() == null ? Map.of() : wb.getTypeData());
        td.putAll(RefChannelRules.typeData(form, body));
        List<Map<String, Object>> workDays = listOfMaps(body, "work_days");
        if (!workDays.isEmpty()) {
            td.put("b2bWorkDays", workDays);
        }
        wb.setTypeData(td.isEmpty() ? null : td);
        wb = waybills.save(wb);
        for (Map<String, Object> f : listOfMaps(body, "fuels")) {
            addFuel(wb.getId(), null, f);
        }

        Integer entryOdometer = intVal(body, "indication_counter_entry");
        if (entryOdometer == null) {
            return wb;
        }
        return closeTrip(form, wb, body, entryOdometer);
    }

    private Waybill closeTrip(Form form, Waybill wb, Map<String, Object> body, int entryOdometer) {
        String dispatcher = null;
        if (has(body, "employee_rma")) {
            Map<String, Object> e = masterData.findEmployee(str(body, "employee_rma"))
                    .orElseThrow(() -> new NotFoundException("Employee not found"));
            if (!Integer.valueOf(3).equals(intOrNull(e.get("type")))) {
                throw new UnprocessableException("employee_rma для закрытия ПЛ должен быть диспетчером (type 3).");
            }
            dispatcher = str(body, "employee_rma");
        } else if (wb.getDispatcherRma() != null) {
            dispatcher = wb.getDispatcherRma();
        }
        if (dispatcher == null) {
            throw new UnprocessableException("Для закрытия ПЛ укажите employee_rma диспетчера.");
        }
        UUID id = wb.getId();
        switch (wb.getStatus()) {
            case DRAFT, CREATED, MED_REJECTED, TECH_REJECTED ->
                    throw new ConflictException("Waybill is not confirmed by doctor and mechanic yet (%s)".formatted(wb.getStatus()));
            case AWAITING_PAYMENT, PAID -> throw new ConflictException("Waybill is awaiting payment (%s)".formatted(wb.getStatus()));
            case READY -> {
                waybillService.issue(id, "B2B");
                waybillService.activate(id, dispatcher, intVal(body, "indication_counter_exit"));
            }
            case ISSUED -> waybillService.activate(id, dispatcher, intVal(body, "indication_counter_exit"));
            case ACTIVE -> { /* уже на линии */ }
            default -> throw new ConflictException("Waybill cannot be closed in status %s".formatted(wb.getStatus()));
        }
        materializeWorkDays(waybills.findById(id).orElseThrow());
        WaybillService.ReturnMetrics metrics = returnMetrics(form, body);
        return waybillService.returnTrip(id, dispatcher, entryOdometer, null, metrics);
    }

    /** Рабочие дни legacy {@code work_days} (из тела или отложенные при создании) → строки WorkDay + топливо дня. */
    private void materializeWorkDays(Waybill wb) {
        Map<String, Object> td = wb.getTypeData();
        if (td == null || !(td.get("b2bWorkDays") instanceof List<?>)) {
            return;
        }
        List<Map<String, Object>> days = listOfMaps(td, "b2bWorkDays");
        for (Map<String, Object> d : days) {
            LocalDate date = date(d, "date");
            if (date == null) {
                continue;
            }
            LocalTime clientTime = time(d, "client_time");
            UUID clientId = uuidOrNull(str(d, "client_id"));
            WorkDay day = workDayService.addWorkDay(wb.getId(), date, time(d, "exit_time"), time(d, "entry_time"),
                    intVal(d, "indication_counter_exit"), intVal(d, "indication_counter_entry"), intVal(d, "laps"),
                    num(d, "earning"), RefChannelRules.hours(time(d, "conditioner_time")), clientId, clientTime);
            for (Map<String, Object> f : listOfMaps(d, "fuels")) {
                addFuel(wb.getId(), day.getId(), f);
            }
        }
        Map<String, Object> cleaned = new LinkedHashMap<>(td);
        cleaned.remove("b2bWorkDays");
        cleaned.put("b2bWorkDaysMaterialized", days.size());
        wb.setTypeData(cleaned);
        waybills.save(wb);
    }

    static WaybillService.ReturnMetrics returnMetrics(Form form, Map<String, Object> body) {
        Double transportWork = null;
        Double conditioner = null;
        String arrival = null;
        if (form.isPassengerDaily()) {
            // 1-АД: круги, выручка и «гашти ибтидоӣ» — как при возврате диспетчером (сверка 25.09, A23): лист без
            // рабочих дней сохраняет их рабочим днём, и расчёт/отчёты видят выручку. Раньше круги уходили в
            // «ездки Z» (грузовой показатель), а earning не доходил до расчёта вовсе.
            BigDecimal h = RefChannelRules.hours(time(body, "conditioner_time"));
            conditioner = h == null ? null : h.doubleValue();
            String a = str(body, "begin_path_a");
            String b = str(body, "begin_path_b");
            return new WaybillService.ReturnMetrics(null, null, conditioner, null, null, null,
                    intVal(body, "number_lap"), num(body, "earning"),
                    a.isBlank() ? null : a, b.isBlank() ? null : b);
        }
        if (form == Form.WAYBILL5BBM) {
            BigDecimal cap = num(body, "cargo_capacity");
            BigDecimal dist = num(body, "cargo_distance");
            if (cap != null && dist != null) {
                transportWork = cap.multiply(dist).doubleValue();
            }
            LocalDateTime at = dateTime(body, "arrival_time");
            arrival = at == null ? null : at.toString();
        }
        return new WaybillService.ReturnMetrics(transportWork, null, conditioner, null, arrival, null);
    }

    // ------------------------------------------------------------------ confirm (врач/механик по РМА)

    /**
     * Legacy {@code POST waybill/confirm}: сотрудник type 1 (врач) / 2 (механик) подтверждает ПЛ; повтор — 409
     * «This employee has already confirmed this waybill»; иной тип — 409 «This employee is not doctor or mechanic».
     */
    @Transactional
    public boolean confirm(Map<String, Object> body) {
        if (!str(body, "organization_rma").matches("\\d{9,10}")) {
            throw new UnprocessableException(RefChannelRules.MSG_ORG);
        }
        Integer typeCode = intVal(body, "waybill_type");
        Form form = Form.byCode(typeCode);
        if (form == null) {
            throw new UnprocessableException("Тип путевки должен быть от 1 до 6.");
        }
        if (!has(body, "waybill_id")) {
            throw new UnprocessableException("Параметр waybill_id обязателен.");
        }
        if (!has(body, "employee_rma")) {
            throw new UnprocessableException("Параметр employee_rma обязателен.");
        }
        Map<String, Object> org = requireOrganization(body);
        String employeeRma = str(body, "employee_rma");
        Map<String, Object> employee = masterData.findEmployee(employeeRma)
                .orElseThrow(() -> new NotFoundException("Employee not found"));
        UUID id = uuidOrNull(str(body, "waybill_id"));
        Waybill wb = id == null ? null : waybills.findById(id).orElse(null);
        if (wb == null || !form.types().contains(wb.getWaybillType()) || !s(org.get("rma")).equals(wb.getOrganizationRma())) {
            throw new NotFoundException("Waybill not found or does not belong to this organization");
        }
        Integer type = intOrNull(employee.get("type"));
        if (Integer.valueOf(1).equals(type)) {
            if (wb.isMedPassed()) {
                throw new ConflictException("This employee has already confirmed this waybill");
            }
            waybillService.confirmMed(wb.getId(), employeeRma, true, Map.of("source", SOURCE));
            return true;
        }
        if (Integer.valueOf(2).equals(type)) {
            if (wb.isTechPassed()) {
                throw new ConflictException("This employee has already confirmed this waybill");
            }
            waybillService.confirmTech(wb.getId(), employeeRma, true, Map.of("source", SOURCE), null);
            return true;
        }
        throw new ConflictException("This employee is not doctor or mechanic");
    }

    // ------------------------------------------------------------------ remain_fuel

    /** Legacy {@code GET remain_fuel}: остаток топлива по ТС из последнего ПЛ (e-Waybill: {@link FuelPrefillService}). */
    @Transactional(readOnly = true)
    public Map<String, Object> remainFuel(String transportRegistrationNumber, String waybillType, Integer fuelId) {
        if (transportRegistrationNumber == null || transportRegistrationNumber.isBlank()) {
            throw new UnprocessableException(RefChannelRules.MSG_TRANSPORT);
        }
        if (waybillType != null && !waybillType.isBlank() && !waybillType.equals("waybill3c") && !waybillType.equals("waybill1a")) {
            throw new UnprocessableException("waybill_type: допустимые значения waybill3c, waybill1a.");
        }
        if (fuelId == null || fuelId < 1 || fuelId > 5) {
            throw new UnprocessableException("Параметр fuel_id обязателен (1–5).");
        }
        String reg = transportRegistrationNumber.trim().toUpperCase();
        masterData.findVehicle(reg).orElseThrow(() -> new NotFoundException("Transport not found"));
        FuelPrefillService.FuelPrefill p = fuelPrefill.forVehicle(reg, fuelId.shortValue(), null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("remain", p.found() && p.remainBeforeExit() != null ? p.remainBeforeExit() : BigDecimal.ZERO);
        out.put("be_given", p.beGiven());
        out.put("source_waybill_number", p.sourceWaybillNumber());
        return out;
    }

    // ------------------------------------------------------------------ view

    public static View view(Waybill wb) {
        Map<String, Object> org = wb.getOrganizationSnapshot();
        Map<String, Object> vehicle = wb.getVehicleSnapshot();
        Map<String, Object> driver = wb.getDriverSnapshot();
        Form form = wb.getWaybillType() == null ? null : Form.ofType(wb.getWaybillType());
        Map<String, Object> company = new LinkedHashMap<>();
        company.put("id", snap(org, "id"));
        company.put("rma", org == null ? wb.getOrganizationRma() : snap(org, "rma"));
        company.put("name", snap(org, "name"));
        company.put("kpp", snap(org, "kpp"));
        Map<String, Object> parking = new LinkedHashMap<>();
        parking.put("id", snap(vehicle, "id"));
        parking.put("registration_number", wb.getVehicleRegNumber());
        parking.put("vincode", snap(vehicle, "vincode"));
        parking.put("transport_type_id", snap(vehicle, "transportType"));
        Map<String, Object> timesheet = new LinkedHashMap<>();
        timesheet.put("id", snap(driver, "id"));
        timesheet.put("full_name", snap(driver, "fullName"));
        timesheet.put("rma", wb.getDriverRma());
        return new View(wb.getId(), wb.getNumber(), wb.getStatus() == null ? null : wb.getStatus().name(),
                form == null ? null : form.key(), form == null ? null : form.legacyCode(),
                wb.getValidFrom(), wb.getValidTo(), wb.getOdometerExit(), wb.getOdometerEntry(),
                wb.getRoute(), wb.getSchedule(), wb.getSpecialMark(), wb.isMedPassed(), wb.isTechPassed(),
                company, parking, timesheet, wb.getSecondDriverRma(), wb.getTypeData(), wb.getCreatedAt());
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> requireOrganization(Map<String, Object> body) {
        String rma = str(body, "organization_rma");
        Map<String, Object> org = masterData.findOrganization(rma).orElseThrow(() -> new NotFoundException("Organization not found"));
        String kpp = str(body, "organization_kpp");
        if (!kpp.isEmpty() && !kpp.equals(s(org.get("kpp")))) {
            throw new NotFoundException("Organization with specified RMA and KPP not found");
        }
        return org;
    }

    private static boolean sameOrg(Map<String, Object> org, Map<String, Object> subject) {
        String orgId = s(org.get("id"));
        String subjectOrg = s(subject.get("organizationId"));
        return subjectOrg.isEmpty() || orgId.equals(subjectOrg);
    }

    /** {@code route_id} (номер/название/UUID маршрута master-data) или {@code route} (название) → строка маршрута ПЛ. */
    private String resolveRoute(Map<String, Object> body) {
        String routeId = str(body, "route_id");
        if (!routeId.isEmpty()) {
            Map<String, Object> r = masterData.findRoute(routeId)
                    .or(() -> masterData.listRoutes().stream().filter(x -> routeId.equalsIgnoreCase(s(x.get("id")))).findFirst())
                    .orElseThrow(() -> new UnprocessableException("Маршрут с указанным ID не найден."));
            String number = s(r.get("number"));
            return number.isEmpty() ? s(r.get("name")) : number;
        }
        return nullIfBlank(str(body, "route"));
    }

    private void addFuel(UUID waybillId, UUID workDayId, Map<String, Object> f) {
        workDayService.addFuel(waybillId, workDayId, intVal(f, "fuel_id").shortValue(),
                num(f, "fuel_given"), num(f, "remain_fuel_before_exit"), num(f, "remain_fuel_entry"),
                num(f, "additional"), num(f, "returned"), num(f, "coef_below_0"), num(f, "be_given"));
    }

    /** Момент выезда: {@code created_at} (дата) + {@code exit_date} (H:i) для 1-АД, либо дата-время {@code exit_date}, либо сейчас. */
    private static OffsetDateTime exitDateTime(Form form, Map<String, Object> body) {
        ZoneId zone = ZoneId.systemDefault();
        if (form.isPassengerDaily()) {
            LocalDate day = date(body, "created_at");
            LocalTime t = time(body, "exit_date");
            if (t != null) {
                return (day == null ? LocalDate.now(zone) : day).atTime(t).atZone(zone).toOffsetDateTime();
            }
        } else if (has(body, "exit_date")) {
            LocalDateTime dt = dateTime(body, "exit_date");
            if (dt != null) {
                return dt.atZone(zone).toOffsetDateTime();
            }
        }
        return OffsetDateTime.now();
    }

    private static String s(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private static String snap(Map<String, Object> m, String key) {
        return m == null || m.get(key) == null ? null : String.valueOf(m.get(key));
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static UUID uuidOrNull(String s) {
        try {
            return s == null || s.isBlank() ? null : UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Integer intOrNull(Object o) {
        return o instanceof Number n ? n.intValue() : o != null && !o.toString().isBlank() ? Integer.valueOf(o.toString()) : null;
    }
}
