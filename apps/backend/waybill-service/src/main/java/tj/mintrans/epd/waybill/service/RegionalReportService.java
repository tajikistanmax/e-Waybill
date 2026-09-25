package tj.mintrans.epd.waybill.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.calc.WaybillCalcAssembler;
import tj.mintrans.epd.waybill.calc.model.PassengerCalcResult;
import tj.mintrans.epd.waybill.calc.model.PassengerMetrics;
import tj.mintrans.epd.waybill.calc.report.PassengerVolumeTrend;
import tj.mintrans.epd.waybill.calc.report.RegionalCountReport;
import tj.mintrans.epd.waybill.calc.report.RegionalReport;
import tj.mintrans.epd.waybill.config.CurrentUser;
import tj.mintrans.epd.waybill.config.TenantScope;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillPlan;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.repository.WaybillPlanRepository;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.web.error.ApiErrors.ForbiddenException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Сводный региональный отчёт Минтранса (перенос {@code WaybillGeneralReport}, §6):
 * факт по завершённым пассажирским ПЛ за отчётный период текущего и прошлого года,
 * годовой план из {@link WaybillPlan}, иерархия регион → город → предприятие.
 *
 * <p>Доступ — только платформенные аналитические роли (регион/республика); тенант
 * (предприятие) собственный сводный отчёт не строит.</p>
 */
@Service
public class RegionalReportService {

    /** Виды ПЛ тренда панели по умолчанию (legacy dashboard/ebus — только троллейбус; у нас оба). */
    static final Set<WaybillType> TREND_TYPES = Set.of(WaybillType.WB_BUS, WaybillType.WB_TROLLEYBUS);

    private final WaybillPeriodScan scan;
    private final WaybillPlanRepository plans;
    private final WaybillCalcAssembler assembler;
    private final CurrentUser currentUser;
    private final TenantScope tenantScope;
    private final tj.mintrans.epd.waybill.client.MasterDataClient masterData;
    private final WaybillRepository waybills;

    public RegionalReportService(WaybillPeriodScan scan, WaybillPlanRepository plans,
                                 WaybillCalcAssembler assembler, CurrentUser currentUser,
                                 TenantScope tenantScope,
                                 tj.mintrans.epd.waybill.client.MasterDataClient masterData,
                                 WaybillRepository waybills) {
        this.scan = scan;
        this.plans = plans;
        this.assembler = assembler;
        this.currentUser = currentUser;
        this.tenantScope = tenantScope;
        this.masterData = masterData;
        this.waybills = waybills;
    }

    /** Транспортный вид ТС + норма листов на стоянку по виду ПЛ (§6.4). */
    private static int[] normSpec(WaybillType type) {
        return switch (type) {
            case WB_MINIBUS -> new int[]{3, 6};       // 1-А: микроавтобус, 6 листов/стоянку
            case WB_TAXI, WB_CAR -> new int[]{4, 4};  // 3-С: легковой, 4
            case WB_TRUCK, WB_DANGEROUS -> new int[]{5, 2}; // 2-Б: грузовой, 2
            case WB_TRUCK_INTL -> new int[]{6, 0};    // 5Б-БМ: грузовой межд., 0
            case WB_BUS -> new int[]{1, 0};
            case WB_TROLLEYBUS -> new int[]{2, 0};
            default -> new int[]{0, 0};
        };
    }

    /**
     * Отчёт «Норматив выдачи путевых листов» (§6.4) по выбранному виду ПЛ.
     *
     * @param typeCompany фильтр «ведомственный / общий» (см. {@code Organization.typeCompany}:
     *        1 — общего пользования, 2 — ведомственная); {@code null} — без фильтра, все организации.
     */
    @Transactional(readOnly = true)
    public tj.mintrans.epd.waybill.calc.report.WaybillNormReport waybillNorm(
            WaybillType type, java.time.LocalDate from, java.time.LocalDate to, Short typeCompany) {
        if (currentUser.isTenantScoped()) {
            throw new ForbiddenException("Отчёт доступен только Минтрансу");
        }
        int[] spec = normSpec(type);
        int transportType = spec[0];
        int mustGive = spec[1];

        // Выдано ПЛ данного вида за период — по organization_rma.
        Map<String, Long> issuedByOrg = new LinkedHashMap<>();
        // Потоком по периоду (WaybillPeriodScan, счётный — без лимита), а не findAll() — после Ф5 2,3 млн ПЛ.
        // 3-С в legacy — одна форма (waybill3c): «такси» и «легковой» считаются вместе (сверка 25.09, D9).
        java.util.Set<WaybillType> types = (type == WaybillType.WB_TAXI || type == WaybillType.WB_CAR)
                ? java.util.EnumSet.of(WaybillType.WB_TAXI, WaybillType.WB_CAR) : java.util.EnumSet.of(type);
        scan.forEachAll(from, to, null, wb -> {
            if (!types.contains(wb.getWaybillType()) || wb.getNumber() == null
                    || wb.getStatus() == WaybillStatus.CANCELLED) {
                return;
            }
            issuedByOrg.merge(wb.getOrganizationRma(), 1L, Long::sum);
        });

        // Организации: из справочника (все) + из факта; фильтр «ведомственный/общий» —
        // на уровне справочника, до построения строк (typeCompany == null — без фильтра).
        Map<String, Map<String, Object>> orgByRma = new LinkedHashMap<>();
        for (Map<String, Object> o : masterData.listOrganizations()) {
            if (o.get("rma") == null) {
                continue;
            }
            if (typeCompany != null && !typeCompany.equals(parseShort(o.get("typeCompany")))) {
                continue;
            }
            orgByRma.put(o.get("rma").toString(), o);
        }
        if (typeCompany == null) {
            issuedByOrg.keySet().forEach(rma -> orgByRma.putIfAbsent(rma, Map.of("rma", rma, "name", rma)));
        }

        // Стоянки (ТС нужного вида) по всем организациям — ОДНИМ агрегатным запросом к master-data,
        // а не списком ТС каждой организации (тысячи HTTP-вызовов → 429 rate-limit).
        Map<String, Long> parkingsByOrg = masterData.countVehiclesByOrganization(transportType == 0 ? null : transportType);

        List<tj.mintrans.epd.waybill.calc.report.WaybillNormReport.Row> rows = new ArrayList<>();
        long tIssued = 0;
        long tParkings = 0;
        long tMustGive = 0;
        for (Map.Entry<String, Map<String, Object>> e : orgByRma.entrySet()) {
            String rma = e.getKey();
            Map<String, Object> org = e.getValue();
            long issued = issuedByOrg.getOrDefault(rma, 0L);
            long parkings = parkingsByOrg.getOrDefault(rma, 0L);
            if (issued == 0 && parkings == 0) {
                continue;
            }
            double perParking = parkings > 0 ? Math.round((double) issued / parkings * 1000.0) / 1000.0 : 0;
            long norm = parkings * mustGive;
            Short regionId = org.get("regionId") != null ? parseShort(org.get("regionId")) : null;
            rows.add(new tj.mintrans.epd.waybill.calc.report.WaybillNormReport.Row(
                    rma, str(org.get("name")), regionTitle(regionId),
                    issued, parkings, perParking, norm, issued - norm));
            tIssued += issued;
            tParkings += parkings;
            tMustGive += norm;
        }
        rows.sort((a, b) -> a.organizationName().compareToIgnoreCase(b.organizationName()));
        var totals = new tj.mintrans.epd.waybill.calc.report.WaybillNormReport.Row(
                "", "ИТОГО", "", tIssued, tParkings,
                tParkings > 0 ? Math.round((double) tIssued / tParkings * 1000.0) / 1000.0 : 0,
                tMustGive, tIssued - tMustGive);
        return new tj.mintrans.epd.waybill.calc.report.WaybillNormReport(
                type.name(), type.legacyForm(), mustGive, from, to, rows, totals);
    }

    private static Short parseShort(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return Short.valueOf(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Пассажирский сводный отчёт (объём тыс. пасс., оборот млн пасс-км). */
    @Transactional(readOnly = true)
    public RegionalReport transportation(LocalDate from, LocalDate to, Short typeCompany) {
        return transportationReport("PASSENGER", from, to, typeCompany, null);
    }

    /**
     * Сводный отчёт по перевозкам с отбором конкретной формы ПЛ (Шакли 3-С / 1-А / 2-Б / 5Б-БМ),
     * как в старой платформе: {@code bill} задаёт разрез (PASSENGER/CARGO), {@code type} —
     * форму внутри разреза ({@code null} — все формы разреза). MIGRATION.md 6.3.
     */
    @Transactional(readOnly = true)
    public RegionalReport transportation(String bill, LocalDate from, LocalDate to, Short typeCompany, WaybillType type) {
        return transportationReport("CARGO".equalsIgnoreCase(bill) ? "CARGO" : "PASSENGER", from, to, typeCompany, type);
    }

    /** Грузовой сводный отчёт (объём тыс. тонн, оборот млн т-км) — формы 2-Б / 5Б-БМ. */
    @Transactional(readOnly = true)
    public RegionalReport cargoTransportation(LocalDate from, LocalDate to, Short typeCompany) {
        return transportationReport("CARGO", from, to, typeCompany, null);
    }

    /**
     * @param typeCompany фильтр «ведомственный / общий» ({@code Organization.typeCompany}:
     *        1 — общего пользования, 2 — ведомственная); {@code null} — без фильтра, все организации.
     */
    private RegionalReport transportationReport(String bill, LocalDate from, LocalDate to, Short typeCompany, WaybillType only) {
        if (currentUser.isTenantScoped()) {
            throw new ForbiddenException("Сводный региональный отчёт доступен только Минтрансу");
        }
        boolean cargo = "CARGO".equals(bill);
        int year = to.getYear();
        int prevYear = year - 1;
        LocalDate prevFrom = from.minusYears(1);
        LocalDate prevTo = to.minusYears(1);
        // Отчётный период укладывается в один календарный месяц — план ищем сперва
        // по конкретному месяцу (легаси Y-m), с фолбэком на годовой план (§3 аудита).
        boolean singleMonth = from.getYear() == to.getYear() && from.getMonthValue() == to.getMonthValue();
        Short targetMonth = singleMonth ? (short) from.getMonthValue() : null;

        // Факт: объём и оборот по каждому завершённому ПЛ выбранного вида — два прохода потоком
        // по периоду (текущий и тот же период прошлого года), а не findAll() (Ф5: 2,3 млн ПЛ).
        Map<String, Fact> factByOrg = new LinkedHashMap<>();
        scan.forEachCompleted(from, to, null, wb -> absorbFact(wb, cargo, true, factByOrg, only));
        scan.forEachCompleted(prevFrom, prevTo, null, wb -> absorbFact(wb, cargo, false, factByOrg, only));
        // Регион и город — из справочника, если снимок организации в ПЛ их не содержит.
        Map<String, Map<String, Object>> directory = organizationsByRma();
        factByOrg.forEach((rma, f) -> f.fillGeographyFrom(directory.get(rma)));

        // План по предприятиям на оба года: годовая строка (plan_month IS NULL) и, если
        // отчётный период — один месяц, месячная строка на этот месяц (она приоритетнее).
        String planKind = cargo ? "CARGO" : "PASSENGER";
        Map<String, WaybillPlan> yearlyCur = new LinkedHashMap<>();
        Map<String, WaybillPlan> yearlyPrev = new LinkedHashMap<>();
        Map<String, WaybillPlan> monthlyCur = new LinkedHashMap<>();
        Map<String, WaybillPlan> monthlyPrev = new LinkedHashMap<>();
        for (WaybillPlan p : plans.findByPlanYearInAndPlanKind(List.of(year, prevYear), planKind)) {
            if (p.getOrganizationRma() == null) {
                continue;
            }
            boolean isCurYear = p.getPlanYear() == year;
            if (p.getPlanMonth() == null) {
                (isCurYear ? yearlyCur : yearlyPrev).put(p.getOrganizationRma(), p);
            } else if (singleMonth && p.getPlanMonth().equals(targetMonth)) {
                (isCurYear ? monthlyCur : monthlyPrev).put(p.getOrganizationRma(), p);
            }
            // Месячные строки на другой месяц — вне отчётного периода, не участвуют.
        }
        Map<String, WaybillPlan> planCur = new LinkedHashMap<>(yearlyCur);
        planCur.putAll(monthlyCur); // месячный план на целевой месяц — приоритетнее годового
        Map<String, WaybillPlan> planPrev = new LinkedHashMap<>(yearlyPrev);
        planPrev.putAll(monthlyPrev);

        // Фильтр «ведомственный/общий» — на уровне набора организаций, до построения иерархии.
        if (typeCompany != null) {
            Set<String> allowed = allowedRmas(typeCompany);
            factByOrg.keySet().retainAll(allowed);
            planCur.keySet().retainAll(allowed);
            planPrev.keySet().retainAll(allowed);
        }

        // Все организации, попавшие в факт или план.
        Map<String, RegionKey> orgRegion = new LinkedHashMap<>();
        factByOrg.forEach((rma, f) -> orgRegion.put(rma, new RegionKey(f.regionId, f.regionTitle(), f.cityTitle(), f.companyTitle)));
        planCur.forEach((rma, p) -> orgRegion.putIfAbsent(rma, new RegionKey(p.getRegionId(), regionTitle(p.getRegionId()), "—", rma)));
        planPrev.forEach((rma, p) -> orgRegion.putIfAbsent(rma, new RegionKey(p.getRegionId(), regionTitle(p.getRegionId()), "—", rma)));

        // Иерархия регион → город → предприятие.
        Map<String, Map<String, List<RegionalReport.Company>>> tree = new LinkedHashMap<>();
        Map<String, Short> regionIdByTitle = new LinkedHashMap<>();
        for (Map.Entry<String, RegionKey> e : orgRegion.entrySet()) {
            String rma = e.getKey();
            RegionKey rk = e.getValue();
            Fact f = factByOrg.get(rma);
            WaybillPlan pc = planCur.get(rma);
            WaybillPlan pp = planPrev.get(rma);
            RegionalReport.Indicators ind = RegionalReport.Indicators.build(
                    pc != null ? pc.getVolumeThousand() : 0,
                    f != null ? f.volumeCur : 0,
                    pp != null ? pp.getVolumeThousand() : 0,
                    f != null ? f.volumePrev : 0,
                    pc != null ? pc.getRotationMillion() : 0,
                    f != null ? f.rotationCur : 0,
                    pp != null ? pp.getRotationMillion() : 0,
                    f != null ? f.rotationPrev : 0);
            regionIdByTitle.put(rk.regionTitle(), rk.regionId());
            tree.computeIfAbsent(rk.regionTitle(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(rk.cityTitle(), k -> new ArrayList<>())
                    .add(new RegionalReport.Company(rk.companyTitle(), rma, ind));
        }

        List<RegionalReport.Region> regions = new ArrayList<>();
        RegionalReport.Indicators grand = RegionalReport.Indicators.zero();
        for (Map.Entry<String, Map<String, List<RegionalReport.Company>>> re : tree.entrySet()) {
            List<RegionalReport.City> cities = new ArrayList<>();
            RegionalReport.Indicators regionTotals = RegionalReport.Indicators.zero();
            for (Map.Entry<String, List<RegionalReport.Company>> ce : re.getValue().entrySet()) {
                RegionalReport.Indicators cityTotals = RegionalReport.Indicators.zero();
                for (RegionalReport.Company c : ce.getValue()) {
                    cityTotals = cityTotals.plus(c.totals());
                }
                cities.add(new RegionalReport.City(ce.getKey(), ce.getValue(), cityTotals));
                regionTotals = regionTotals.plus(cityTotals);
            }
            regions.add(new RegionalReport.Region(re.getKey(), regionIdByTitle.get(re.getKey()), cities, regionTotals));
            grand = grand.plus(regionTotals);
        }

        return new RegionalReport(bill, "transportation", from, to, year, prevYear, regions, grand);
    }

    /** Вклад одного завершённого ПЛ в факт сводного отчёта (текущий период — {@code cur}, иначе прошлогодний). */
    private void absorbFact(Waybill wb, boolean cargo, boolean cur, Map<String, Fact> factByOrg, WaybillType only) {
        boolean typeOk = (cargo ? isCargo(wb.getWaybillType()) : isPassenger(wb.getWaybillType()))
                && (only == null || only == wb.getWaybillType());
        // Отработанный лист — закрытый ИЛИ архивный (архив ставится по сроку ретенции уже после
        // закрытия). Иначе сводный отчёт Минтранса за прошлые периоды выдавал нули по всей стране.
        if (!WaybillStatus.FINISHED.contains(wb.getStatus()) || !typeOk || wb.getCreatedAt() == null) {
            return;
        }
        double volume;
        double rotation;
        if (cargo) {
            WaybillCalcAssembler.View v = assembler.calculate(wb, WaybillCalcAssembler.Supplement.empty());
            if (v.cargo() == null) {
                return;
            }
            double p = v.cargo().transportWork();            // P, т·км
            double dist = Math.max(1, v.cargo().distanceKm());
            volume = (p / dist) / 1000.0;                    // тыс. тонн (средняя загрузка × 1 ходка)
            rotation = p / 1_000_000.0;                      // млн т·км
        } else {
            PassengerMetrics m = metrics(wb);
            if (m == null) {
                return;
            }
            volume = m.passengerCount() / 1000.0;            // тыс. пасс.
            rotation = m.passengerTurnover() / 1_000_000.0;  // млн пасс-км
        }
        Fact f = factByOrg.computeIfAbsent(wb.getOrganizationRma(), k -> new Fact(wb));
        f.absorb(wb);
        if (cur) {
            f.volumeCur += volume;
            f.rotationCur += rotation;
        } else {
            f.volumePrev += volume;
            f.rotationPrev += rotation;
        }
    }

    private static boolean isCargo(WaybillType t) {
        return t == WaybillType.WB_TRUCK || t == WaybillType.WB_TRUCK_INTL
                || t == WaybillType.WB_SPECIAL || t == WaybillType.WB_DANGEROUS;
    }

    /**
     * РМА организаций справочника, чей {@code typeCompany} совпадает с фильтром
     * (1 — общего пользования, 2 — ведомственная). Используется для сужения набора
     * организаций сводных отчётов Минтранса (§1 аудита — «ведомственный/общий» разрез).
     */
    /**
     * Справочник организаций по РМА — запасной источник региона и города для отчётов.
     * Снимок организации в перенесённых путевых листах содержит только РМА и название, поэтому
     * без этого запаса вся история попадала в группу «Без региона» и разрез Минтранса
     * «регион → город → предприятие» терял смысл (находка приёмки 22.09.2026).
     */
    private Map<String, Map<String, Object>> organizationsByRma() {
        Map<String, Map<String, Object>> byRma = new HashMap<>();
        for (Map<String, Object> o : masterData.listOrganizations()) {
            Object rma = o.get("rma");
            if (rma != null) {
                byRma.put(rma.toString(), o);
            }
        }
        return byRma;
    }

    private Set<String> allowedRmas(Short typeCompany) {
        Set<String> out = new HashSet<>();
        for (Map<String, Object> o : masterData.listOrganizations()) {
            Object rma = o.get("rma");
            if (rma != null && typeCompany.equals(parseShort(o.get("typeCompany")))) {
                out.add(rma.toString());
            }
        }
        return out;
    }

    /** Плоский список планов (для CRUD-экрана Минтранса). */
    @Transactional(readOnly = true)
    public List<WaybillPlan> listPlans(String kind) {
        return plans.findByPlanKindOrderByPlanYearDesc(kind == null || kind.isBlank() ? "PASSENGER" : kind);
    }

    /**
     * Сводный отчёт «Количество путевых листов» (§6.3, 19 счётчиков + 2 доп. счётчика
     * накладных к грузовым ПЛ, §2 аудита).
     *
     * @param bill {@code PASSENGER} | {@code CARGO} | {@code ALL}
     * @param typeCompany фильтр «ведомственный/общий» ({@code Organization.typeCompany});
     *        {@code null} — без фильтра, все организации.
     */
    @Transactional(readOnly = true)
    public RegionalCountReport countWaybills(String bill, LocalDate from, LocalDate to, Short typeCompany) {
        if (currentUser.isTenantScoped()) {
            throw new ForbiddenException("Сводный региональный отчёт доступен только Минтрансу");
        }
        String kind = bill == null ? "ALL" : bill.toUpperCase();
        int year = to.getYear();
        int prevYear = year - 1;

        // «Аз аввали сол» — строго с 1 января года начала периода (legacy AbstractCalcCountTotal:
        // exit_dateY == currYear && month ≤ currYearMonth). Прежний сдвиг на декабрь в январе
        // (записанный как «особенность §6.3») в legacy не подтвердился — сверка 25.09, D8.
        LocalDate startOfYear = LocalDate.of(from.getYear(), 1, 1);
        // Предыдущий месяц: полный месяц → предыдущий календарный; иначе — сдвиг периода на месяц назад.
        boolean fullMonth = from.getDayOfMonth() == 1 && to.equals(to.withDayOfMonth(to.lengthOfMonth()));
        LocalDate prevFrom;
        LocalDate prevTo;
        if (fullMonth) {
            LocalDate pm = from.minusMonths(1);
            prevFrom = pm.withDayOfMonth(1);
            prevTo = pm.withDayOfMonth(pm.lengthOfMonth());
        } else {
            prevFrom = from.minusMonths(1);
            prevTo = to.minusMonths(1);
        }

        // Аккумулятор по организации. Один проход потоком по объединённому периоду всех
        // счётчиков (от самой ранней границы до `to`), а не findAll() — после Ф5 в таблице 2,3 млн ПЛ.
        final LocalDate pf = prevFrom;
        final LocalDate pt = prevTo;
        LocalDate scanFrom = startOfYear.minusYears(1);
        for (LocalDate b : List.of(pf, from.minusYears(1), from, startOfYear)) {
            if (b.isBefore(scanFrom)) {
                scanFrom = b;
            }
        }
        Map<String, Acc> byOrg = new LinkedHashMap<>();
        scan.forEachAll(scanFrom, to, null, wb -> {   // счётный отчёт — без лимита строк
            if (!billMatches(kind, wb.getWaybillType())) {
                return;
            }
            LocalDate d = wb.getCreatedAt() == null ? null : wb.getCreatedAt().toLocalDate();
            if (d == null) {
                return;
            }
            // Legacy: аннулированные (soft delete) не считаются вовсе; «коркардшуда» = есть возврат
            // (entry_date). Просроченный без возврата — «фарогирифта», но не обработан.
            if (wb.getStatus() == WaybillStatus.CANCELLED) {
                return;
            }
            boolean issued = wb.getNumber() != null;
            boolean processed = wb.getOdometerEntry() != null;
            Acc a = byOrg.computeIfAbsent(wb.getOrganizationRma(), k -> new Acc(wb));
            a.absorb(wb);
            String veh = wb.getVehicleRegNumber();

            if (inRange(d, from, to)) {
                if (issued) { a.issuedMonth++; if (veh != null) a.vehMonth.add(veh); }
                if (processed) { a.processedMonth++; }
            }
            if (inRange(d, pf, pt)) {
                if (issued) { a.issuedPrevMonth++; if (veh != null) a.vehPrevMonth.add(veh); }
                if (processed) { a.processedPrevMonth++; }
            }
            if (inRange(d, from.minusYears(1), to.minusYears(1))) {
                if (issued && veh != null) { a.vehMonthPrevYear.add(veh); }
            }
            if (inRange(d, startOfYear, to)) {
                if (issued) { a.issuedYtd++; if (veh != null) a.vehYtd.add(veh); }
                if (processed) { a.processedYtd++; }
            }
            if (inRange(d, startOfYear.minusYears(1), to.minusYears(1))) {
                if (issued) { a.issuedYtdPrev++; }
                if (processed) { a.processedYtdPrev++; }
            }
            // Грузовые ПЛ (2-Б/5Б-БМ/спецтехника/опасные грузы), выданные за отчётный период,
            // и сколько из них — с заполненной накладной (§2 аудита). Присутствие накладной
            // определяем по непустому typeData.senderName — оно заполняется первым и вместе
            // со всеми остальными полями накладной в WaybillService.updateConsignment, то есть
            // самый надёжный единичный сигнал «накладная оформлена».
            if (issued && isCargo(wb.getWaybillType()) && inRange(d, from, to)) {
                a.cargoIssued++;
                if (hasConsignment(wb)) {
                    a.cargoWithConsignment++;
                }
            }
        });
        // Регион и город — из справочника, если снимок организации в ПЛ их не содержит.
        Map<String, Map<String, Object>> directory = organizationsByRma();
        byOrg.forEach((rma, a) -> a.fillGeographyFrom(directory.get(rma)));

        // Фильтр «ведомственный/общий» — на уровне набора организаций, до построения иерархии.
        if (typeCompany != null) {
            byOrg.keySet().retainAll(allowedRmas(typeCompany));
        }

        // Иерархия регион → город → предприятие.
        Map<String, Map<String, List<RegionalCountReport.Company>>> tree = new LinkedHashMap<>();
        Map<String, Short> regionIdByTitle = new LinkedHashMap<>();
        for (Map.Entry<String, Acc> e : byOrg.entrySet()) {
            Acc a = e.getValue();
            RegionalCountReport.Counts c = a.toCounts();
            regionIdByTitle.put(a.regionTitle(), a.regionId);
            tree.computeIfAbsent(a.regionTitle(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(a.cityTitle(), k -> new ArrayList<>())
                    .add(new RegionalCountReport.Company(a.companyTitle, e.getKey(), c));
        }

        List<RegionalCountReport.Region> regions = new ArrayList<>();
        RegionalCountReport.Counts grand = RegionalCountReport.Counts.zero();
        for (var re : tree.entrySet()) {
            List<RegionalCountReport.City> cities = new ArrayList<>();
            RegionalCountReport.Counts regionTotals = RegionalCountReport.Counts.zero();
            for (var ce : re.getValue().entrySet()) {
                RegionalCountReport.Counts cityTotals = RegionalCountReport.Counts.zero();
                for (RegionalCountReport.Company co : ce.getValue()) {
                    cityTotals = cityTotals.plus(co.totals());
                }
                cities.add(new RegionalCountReport.City(ce.getKey(), ce.getValue(), cityTotals));
                regionTotals = regionTotals.plus(cityTotals);
            }
            regions.add(new RegionalCountReport.Region(re.getKey(), regionIdByTitle.get(re.getKey()), cities, regionTotals));
            grand = grand.plus(regionTotals);
        }
        return new RegionalCountReport(kind, from, to, year, prevYear, regions, grand);
    }

    /**
     * Отбор по разрезу отчёта: {@code PASSENGER} / {@code CARGO} / {@code ALL} либо имя конкретного
     * вида ПЛ ({@code WB_TRUCK}, {@code WB_TRUCK_INTL}, …) — как в старой платформе, где сводный
     * отчёт строился по выбранной форме (Шакли 3-С / 1-А / 2-Б / 5Б-БМ), MIGRATION.md 6.3.
     */
    private static boolean billMatches(String kind, WaybillType t) {
        boolean passenger = t == WaybillType.WB_BUS || t == WaybillType.WB_TROLLEYBUS || t == WaybillType.WB_MINIBUS
                || t == WaybillType.WB_CAR || t == WaybillType.WB_TAXI || t == WaybillType.WB_PAX_INTL;
        boolean cargo = t == WaybillType.WB_TRUCK || t == WaybillType.WB_TRUCK_INTL
                || t == WaybillType.WB_SPECIAL || t == WaybillType.WB_DANGEROUS;
        return switch (kind) {
            case "PASSENGER" -> passenger;
            case "CARGO" -> cargo;
            case "ALL" -> passenger || cargo;
            default -> t != null && t.name().equals(kind);
        };
    }

    private static boolean inRange(LocalDate d, LocalDate from, LocalDate to) {
        return !d.isBefore(from) && !d.isAfter(to);
    }

    /** Аккумулятор счётчиков по организации. */
    private static final class Acc {
        Short regionId;
        String cityName;
        final String companyTitle;
        long issuedMonth;
        long issuedPrevMonth;
        long issuedYtd;
        long issuedYtdPrev;
        long processedMonth;
        long processedPrevMonth;
        long processedYtd;
        long processedYtdPrev;
        long cargoIssued;
        long cargoWithConsignment;
        final Set<String> vehYtd = new HashSet<>();
        final Set<String> vehMonth = new HashSet<>();
        final Set<String> vehPrevMonth = new HashSet<>();
        final Set<String> vehMonthPrevYear = new HashSet<>();

        Acc(Waybill wb) {
            Map<String, Object> org = wb.getOrganizationSnapshot();
            this.companyTitle = org != null && org.get("name") != null && !org.get("name").toString().isBlank()
                    ? org.get("name").toString() : wb.getOrganizationRma();
        }

        void absorb(Waybill wb) {
            Map<String, Object> org = wb.getOrganizationSnapshot();
            if (org == null) {
                return;
            }
            if (regionId == null && org.get("regionId") != null) {
                try {
                    regionId = Short.valueOf(org.get("regionId").toString());
                } catch (NumberFormatException ignored) {
                    // регион в снимке не число
                }
            }
            if ((cityName == null || cityName.isBlank()) && org.get("cityName") != null) {
                cityName = org.get("cityName").toString();
            }
        }

        /** Регион и город из справочника организаций — когда снимок в ПЛ их не несёт. */
        void fillGeographyFrom(Map<String, Object> org) {
            if (org == null) {
                return;
            }
            if (regionId == null) {
                regionId = parseShort(org.get("regionId"));
            }
            if ((cityName == null || cityName.isBlank()) && org.get("cityName") != null) {
                cityName = org.get("cityName").toString();
            }
        }

        String regionTitle() {
            return RegionalReportService.regionTitle(regionId);
        }

        String cityTitle() {
            return cityName == null || cityName.isBlank() ? "—" : cityName;
        }

        RegionalCountReport.Counts toCounts() {
            return RegionalCountReport.Counts.of(
                    issuedMonth, issuedPrevMonth, issuedYtd, issuedYtdPrev,
                    processedMonth, processedPrevMonth, processedYtd, processedYtdPrev,
                    vehYtd.size(), vehMonth.size(), vehPrevMonth.size(), vehMonthPrevYear.size(),
                    cargoIssued, cargoWithConsignment);
        }
    }

    /**
     * Присутствие накладной у грузового ПЛ — по непустому {@code typeData.senderName}
     * (заполняется через {@code WaybillService.updateConsignment} вместе со всеми
     * остальными полями накладной первым, поэтому взят как единственный сигнал наличия).
     */
    private static boolean hasConsignment(Waybill wb) {
        Map<String, Object> td = wb.getTypeData();
        if (td == null) {
            return false;
        }
        Object sender = td.get("senderName");
        return sender != null && !sender.toString().isBlank();
    }

    /**
     * Тренд пассажирооборота (млн пасс-км) за последние {@code months} месяцев по завершённым
     * автобусным/троллейбусным ПЛ — перенос единственного содержательного графика легаси-панели
     * администратора ({@code Admin\Charts\Ebus\PassengerVolumeController}). В отличие от
     * {@code /regional*} — доступен как остальные {@code ReportController}: тенант видит свою
     * область (компания + филиалы), платформенные роли — все организации.
     */
    @Transactional(readOnly = true)
    public PassengerVolumeTrend passengerVolumeTrend(int months) {
        return passengerVolumeTrend(months, TREND_TYPES);
    }

    /**
     * Тренд по выбранным видам ПЛ (автобус / троллейбус / оба): пассажирооборот завершённых ПЛ
     * и число выписанных ПЛ по месяцам (MIGRATION.md 6.8 — legacy {@code dashboard/ebus}:
     * {@code BillCountsController} + {@code PassengerVolumeController}, 6 месяцев назад + текущий).
     *
     * @param requestedTypes виды ПЛ; {@code null}/пусто — {@link #TREND_TYPES}
     */
    public PassengerVolumeTrend passengerVolumeTrend(int months, Set<WaybillType> requestedTypes) {
        Set<WaybillType> types = requestedTypes == null || requestedTypes.isEmpty() ? TREND_TYPES : requestedTypes;
        int n = Math.max(1, Math.min(months, 24));
        Set<String> scope = tenantScope.isBounded() ? tenantScope.rmas() : null;
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusMonths(n - 1L).withDayOfMonth(1);

        List<String> monthKeys = new ArrayList<>();
        Map<String, Double> byMonth = new LinkedHashMap<>();
        Map<String, Long> countByMonth = new LinkedHashMap<>();
        for (int i = n - 1; i >= 0; i--) {
            LocalDate m = to.minusMonths(i);
            String key = monthKey(m);
            monthKeys.add(key);
            byMonth.put(key, 0.0);
            countByMonth.put(key, 0L);
        }

        Set<String> scanScope = scope != null && scope.contains("__none__") ? Set.of() : scope;

        // Число ПЛ по месяцам — агрегат в БД по всем статусам (legacy COUNT по MONTH(created_at)),
        // без прохода по строкам: в периоде платформенной роли после Ф5 сотни тысяч ПЛ.
        List<Object[]> counts = scanScope == null
                ? waybills.countByMonth(types, WaybillPeriodScan.lower(from), WaybillPeriodScan.upper(to))
                : scanScope.isEmpty() ? List.of()
                : waybills.countByMonthForOrganizations(scanScope, types,
                        WaybillPeriodScan.lower(from), WaybillPeriodScan.upper(to));
        for (Object[] row : counts) {
            String key = String.format("%04d-%02d", ((Number) row[0]).intValue(), ((Number) row[1]).intValue());
            if (countByMonth.containsKey(key)) {
                countByMonth.put(key, ((Number) row[2]).longValue());
            }
        }

        // Пассажирооборот — потоком по завершённым ПЛ периода и области (WaybillPeriodScan), а не findAll();
        // тренд открыт тенантам — их область небольшая, платформенным ролям — весь период n месяцев.
        // Если завершённых листов больше лимита построчного прохода (у платформенной роли после
        // переноса архива — сотни тысяч за 7 месяцев), тренд НЕ падает целиком: число листов по
        // месяцам уже посчитано агрегатом в БД и показывается, пассажирооборот — 0. До 23.09.2026
        // здесь был 422, и график главной панели Минтранса оставался пустым (находка живой проверки).
        try {
            scanTurnover(from, to, scanScope, types, byMonth);
        } catch (tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException tooMany) {
            byMonth.replaceAll((k, v) -> 0.0);
        }

        List<PassengerVolumeTrend.Point> points = new ArrayList<>();
        for (String key : monthKeys) {
            points.add(new PassengerVolumeTrend.Point(key, Math.round(byMonth.get(key) * 100.0) / 100.0,
                    countByMonth.get(key)));
        }
        List<WaybillType> typeList = new ArrayList<>(types);
        typeList.sort(java.util.Comparator.naturalOrder());
        return new PassengerVolumeTrend(n, typeList, points);
    }

    private void scanTurnover(LocalDate from, LocalDate to, Set<String> scanScope, Set<WaybillType> types,
                              Map<String, Double> byMonth) {
        scan.forEachCompleted(from, to, scanScope, wb -> {
            WaybillType type = wb.getWaybillType();
            if (!types.contains(type)) {
                return;
            }
            if (wb.getCreatedAt() == null) {
                return;
            }
            String key = monthKey(wb.getCreatedAt().toLocalDate());
            if (!byMonth.containsKey(key)) {
                return;
            }
            PassengerMetrics m = metrics(wb);
            if (m == null) {
                return;
            }
            byMonth.merge(key, m.passengerTurnover() / 1_000_000.0, Double::sum); // млн пасс-км
        });
    }

    private static String monthKey(LocalDate d) {
        return String.format("%04d-%02d", d.getYear(), d.getMonthValue());
    }

    // ------------------------------------------------------------------

    private PassengerMetrics metrics(Waybill wb) {
        WaybillCalcAssembler.View view = assembler.calculate(wb, WaybillCalcAssembler.Supplement.empty());
        PassengerCalcResult r = view.passenger();
        return r == null ? null : r.passengerMetrics();
    }

    private static boolean isPassenger(WaybillType t) {
        return t == WaybillType.WB_BUS || t == WaybillType.WB_TROLLEYBUS || t == WaybillType.WB_MINIBUS
                || t == WaybillType.WB_CAR || t == WaybillType.WB_TAXI || t == WaybillType.WB_PAX_INTL;
    }

    private static String regionTitle(Short regionId) {
        if (regionId == null) {
            return "Без региона";
        }
        // Коды регионов — как в legacy `regions` (id 1..7) и справочнике региона master-data (V62).
        // До 25.09 подписи 2/3/5 были перепутаны («Согдийская» = ГБАО и т.д.), 6 и 7 — «Регион N».
        return switch (regionId) {
            case 1 -> "г. Душанбе";
            case 2 -> "ГБАО";
            case 3 -> "Согдийская область";
            case 4 -> "Раштская зона (РРП)";
            case 5 -> "Хатлонская область (Бохтарская зона)";
            case 6 -> "Хатлонская область (Кулябская зона)";
            case 7 -> "Гиссарская зона (РРП)";
            default -> "Регион " + regionId;
        };
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private record RegionKey(Short regionId, String regionTitle, String cityTitle, String companyTitle) {
    }

    private static final class Fact {
        Short regionId;
        String cityName;
        final String companyTitle;
        double volumeCur;
        double volumePrev;
        double rotationCur;
        double rotationPrev;

        Fact(Waybill wb) {
            this.companyTitle = snap(wb, "name", wb.getOrganizationRma());
            absorb(wb);
        }

        void absorb(Waybill wb) {
            Map<String, Object> org = wb.getOrganizationSnapshot();
            if (org != null) {
                if (regionId == null && org.get("regionId") != null) {
                    try {
                        regionId = Short.valueOf(org.get("regionId").toString());
                    } catch (NumberFormatException ignored) {
                        // регион в снимке не число — оставляем null
                    }
                }
                if ((cityName == null || cityName.isBlank()) && org.get("cityName") != null) {
                    cityName = org.get("cityName").toString();
                }
            }
        }

        /** Регион и город из справочника организаций — когда снимок в ПЛ их не несёт. */
        void fillGeographyFrom(Map<String, Object> org) {
            if (org == null) {
                return;
            }
            if (regionId == null) {
                regionId = parseShort(org.get("regionId"));
            }
            if ((cityName == null || cityName.isBlank()) && org.get("cityName") != null) {
                cityName = org.get("cityName").toString();
            }
        }

        String regionTitle() {
            return RegionalReportService.regionTitle(regionId);
        }

        String cityTitle() {
            return cityName == null || cityName.isBlank() ? "—" : cityName;
        }

        private static String snap(Waybill wb, String field, String fallback) {
            Map<String, Object> org = wb.getOrganizationSnapshot();
            if (org != null && org.get(field) != null && !org.get(field).toString().isBlank()) {
                return org.get(field).toString();
            }
            return fallback;
        }
    }
}
