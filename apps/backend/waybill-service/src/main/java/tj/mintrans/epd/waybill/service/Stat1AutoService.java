package tj.mintrans.epd.waybill.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.calc.WaybillCalcAssembler;
import tj.mintrans.epd.waybill.calc.model.CargoCalcResult;
import tj.mintrans.epd.waybill.calc.model.PassengerMetrics;
import tj.mintrans.epd.waybill.client.MasterDataClient;
import tj.mintrans.epd.waybill.config.TenantScope;
import tj.mintrans.epd.waybill.domain.ConsignmentNote;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.domain.WorkDay;
import tj.mintrans.epd.waybill.print.PrintZone;
import tj.mintrans.epd.waybill.repository.ConsignmentNoteRepository;
import tj.mintrans.epd.waybill.repository.WorkDayRepository;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Статформа «1-авто» (legacy тип отчёта 4 «Авто», {@code report/custom/bus/type_4.blade.php}; сверка 25.09, D4):
 * строки 01–44 — перевезено грузов и пассажиров, грузо- и пассажирооборот, автомобиле-дни в хозяйстве и в
 * работе, часы, пробег общий и с грузом / пассажирами, число автомобилей — с разбивкой на грузовые,
 * автобусы (с маршрутными такси 1-А), такси (3-С) и троллейбусы.
 *
 * <p>Legacy строит форму по одному бланку (заполнена одна группа строк, прочие — нули) и колонку «с начала
 * года» оставляет пустой. Здесь форма заполняется целиком по всем видам ПЛ организации, «с начала года»
 * считается тем же способом с 1 января года конца периода. Многодневные 1-А / 3-С — по датам рабочих
 * дней (D3).</p>
 */
@Service
public class Stat1AutoService {

    /** Группы строк формы. */
    public enum Cat { TRUCK, BUS, TAXI, TROLLEY }

    /** Строка формы: {@code code} — рамзи сатр; {@code month}/{@code ytd} — {@code null} у заголовка группы. */
    public record Row(String code, String label, String unit, Double month, Double ytd, int level) {
    }

    public record Report(LocalDate from, LocalDate to, LocalDate yearFrom, String organizationRma,
                         List<Row> rows, String note) {
    }

    private final WaybillPeriodScan scan;
    private final WaybillCalcAssembler assembler;
    private final TenantScope tenantScope;
    private final MasterDataClient masterData;
    private final WorkDayRepository workDays;
    private final ConsignmentNoteRepository consignmentNotes;

    public Stat1AutoService(WaybillPeriodScan scan, WaybillCalcAssembler assembler, TenantScope tenantScope,
                            MasterDataClient masterData, WorkDayRepository workDays,
                            ConsignmentNoteRepository consignmentNotes) {
        this.scan = scan;
        this.assembler = assembler;
        this.tenantScope = tenantScope;
        this.masterData = masterData;
        this.workDays = workDays;
        this.consignmentNotes = consignmentNotes;
    }

    /** Накопитель показателей одной группы. */
    static final class Acc {
        double tons;
        double tkm;
        double passengers;
        double pkm;
        final Set<String> vehicleDays = new HashSet<>();
        double minutes;
        double totalKm;
        double paidKm;
        final Set<String> vehicles = new HashSet<>();
        long fleet;
    }

    @Transactional(readOnly = true)
    public Report build(LocalDate from, LocalDate to, String requestedOrg) {
        return build(from, to, requestedOrg, true);
    }

    /**
     * @param withYtd считать колонку «с начала года» — отдельный проход с 1 января (у крупного перевозчика
     *                в разы дольше периода), поэтому по запросу
     */
    @Transactional(readOnly = true)
    public Report build(LocalDate from, LocalDate to, String requestedOrg, boolean withYtd) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new UnprocessableException("Укажите период: начало не позже конца");
        }
        Set<String> scope = resolveScope(requestedOrg);
        Map<Long, Long> fleetByType = fleetByTransportType(scope);

        Map<Cat, Acc> month = collect(from, to, scope);
        applyFleet(month, fleetByType, ChronoUnit.DAYS.between(from, to) + 1);

        LocalDate yearFrom = to.withDayOfYear(1);
        Map<Cat, Acc> ytd;
        String note = null;
        if (!from.isAfter(yearFrom) && from.getYear() == to.getYear()) {
            ytd = month;
        } else if (!withYtd) {
            ytd = null;
        } else {
            try {
                ytd = collect(yearFrom, to, scope);
                applyFleet(ytd, fleetByType, ChronoUnit.DAYS.between(yearFrom, to) + 1);
            } catch (UnprocessableException e) {
                ytd = null;
                note = "Колонка «с начала года» не рассчитана: " + e.getMessage();
            }
        }
        String org = scope == null ? null : String.join(",", scope);
        return new Report(from, to, yearFrom, org, rows(month, ytd), note);
    }

    // ------------------------------------------------------------------ сбор

    private Map<Cat, Acc> collect(LocalDate from, LocalDate to, Set<String> scope) {
        Map<Cat, Acc> acc = new EnumMap<>(Cat.class);
        for (Cat c : Cat.values()) {
            acc.put(c, new Acc());
        }
        WaybillCalcAssembler.Period period = new WaybillCalcAssembler.Period(from, to);
        java.util.function.Consumer<Waybill> handle = wb -> absorb(wb, period, acc);
        scan.forEach(from, to, scope, handle);
        scan.forEachCarryOver(from, to, scope, WaybillCalcAssembler.DAY_SCOPED_TYPES, handle);
        return acc;
    }

    void absorb(Waybill wb, WaybillCalcAssembler.Period period, Map<Cat, Acc> acc) {
        Cat cat = category(wb.getWaybillType());
        if (cat == null || !WaybillReportService.countsInReports(wb)) {
            return;
        }
        WaybillCalcAssembler.View view = assembler.calculate(wb, WaybillCalcAssembler.Supplement.empty(), period);
        if (view.outOfPeriod()) {
            return;
        }
        Acc a = acc.get(cat);
        String vehicle = norm(wb.getVehicleRegNumber());
        a.vehicles.add(vehicle);
        for (LocalDate d : workDates(wb, period)) {
            a.vehicleDays.add(vehicle + "|" + d);
        }
        a.minutes += view.workMinutes();
        if (cat == Cat.TRUCK) {
            CargoCalcResult r = view.cargo();
            if (r == null) {
                return;
            }
            a.tkm += r.transportWork();
            a.totalKm += r.distanceKm();
            List<ConsignmentNote> notes = consignmentNotes.findByWaybillIdOrderByNoteDateAscNumberAsc(wb.getId());
            if (!notes.isEmpty()) {
                // Масса и пробег с грузом — по борхатам (масса замимаи 1 × рейсы, как в «Умумӣ»).
                a.tons += ConsignmentNoteService.Totals.of(notes).weight();
                for (ConsignmentNote n : notes) {
                    double dist = n.getDistance() == null ? 0 : n.getDistance().doubleValue();
                    a.paidKm += n.getKind() == 2 ? dist : dist * Math.max(0, n.getTrips() == null ? 0 : n.getTrips());
                }
            } else if (r.distanceKm() > 0) {
                a.tons += r.transportWork() / r.distanceKm();   // средняя загрузка × 1 ходка — как в «Умумӣ»
            }
            return;
        }
        if (view.passenger() == null) {
            return;
        }
        PassengerMetrics m = view.passenger().passengerMetrics();
        if (m == null) {
            return;
        }
        a.passengers += m.passengerCount();
        a.pkm += m.passengerTurnover();
        a.totalKm += m.totalDistanceKm();
        a.paidKm += m.routeDistanceKm();
    }

    /** Даты работы ТС по листу в периоде: рабочие дни 1-А / 3-С, иначе — день выписки. */
    private List<LocalDate> workDates(Waybill wb, WaybillCalcAssembler.Period period) {
        if (WaybillCalcAssembler.DAY_SCOPED_TYPES.contains(wb.getWaybillType()) && wb.getId() != null) {
            List<LocalDate> dates = new ArrayList<>();
            for (WorkDay d : workDays.findByWaybillIdOrderByWorkDate(wb.getId())) {
                LocalDate date = d.getWorkDate();
                if (date != null && !date.isBefore(period.from()) && !date.isAfter(period.to())) {
                    dates.add(date);
                }
            }
            if (!dates.isEmpty()) {
                return dates;
            }
        }
        return wb.getCreatedAt() == null ? List.of() : List.of(PrintZone.local(wb.getCreatedAt()).toLocalDate());
    }

    static Cat category(WaybillType t) {
        if (t == null) {
            return null;
        }
        return switch (t) {
            case WB_TRUCK, WB_TRUCK_INTL, WB_DANGEROUS, WB_SPECIAL -> Cat.TRUCK;
            // «Автобусҳо (бо назардошти таксомоторҳои маршрутӣ)» — с маршрутными такси (1-А).
            case WB_BUS, WB_MINIBUS, WB_PAX_INTL -> Cat.BUS;
            case WB_CAR, WB_TAXI -> Cat.TAXI;
            case WB_TROLLEYBUS -> Cat.TROLLEY;
        };
    }

    // ------------------------------------------------------------------ парк

    /** Число ТС организаций области по виду транспорта master-data (1 автобус … 6 грузовой межд.). */
    private Map<Long, Long> fleetByTransportType(Set<String> scope) {
        Map<Long, Long> out = new java.util.HashMap<>();
        for (long tt = 1; tt <= 6; tt++) {
            Map<String, Long> byOrg;
            try {
                byOrg = masterData.countVehiclesByOrganization((int) tt);
            } catch (RuntimeException e) {
                byOrg = Map.of();
            }
            long n = 0;
            for (Map.Entry<String, Long> e : byOrg.entrySet()) {
                if (scope == null || scope.contains(e.getKey())) {
                    n += e.getValue() == null ? 0 : e.getValue();
                }
            }
            out.put(tt, n);
        }
        return out;
    }

    /** Автомобиле-дни в хозяйстве = число ТС × дней периода (legacy: parkingCount × дней в месяце). */
    private static void applyFleet(Map<Cat, Acc> acc, Map<Long, Long> fleetByType, long days) {
        acc.get(Cat.BUS).fleet = (fleetByType.getOrDefault(1L, 0L) + fleetByType.getOrDefault(3L, 0L)) * days;
        acc.get(Cat.TROLLEY).fleet = fleetByType.getOrDefault(2L, 0L) * days;
        acc.get(Cat.TAXI).fleet = fleetByType.getOrDefault(4L, 0L) * days;
        acc.get(Cat.TRUCK).fleet = (fleetByType.getOrDefault(5L, 0L) + fleetByType.getOrDefault(6L, 0L)) * days;
    }

    // ------------------------------------------------------------------ строки формы

    private interface Metric {
        double of(Acc a);
    }

    private static List<Row> rows(Map<Cat, Acc> m, Map<Cat, Acc> y) {
        List<Row> rows = new ArrayList<>();
        rows.add(group("1. Автомобилҳои боркаш (бо назардошти пикапҳо, фургонҳои сабукрав, нимядак ва нимприцепҳо)"));
        rows.add(row("01", "бор кашонида шудааст", "ҳазор тонна", m, y, Cat.TRUCK, a -> a.tons / 1000));
        rows.add(row("02", "гардиши бор", "ҳазор ткм", m, y, Cat.TRUCK, a -> a.tkm / 1000));
        rows.add(group("2. Автобусҳо (бо назардошти таксомоторҳои маршрутӣ)"));
        rows.add(row("03", "мусофир кашонида шудааст", "ҳазор мусофир", m, y, Cat.BUS, a -> a.passengers / 1000));
        rows.add(row("04", "гардиши мусофирон", "ҳазор мусофир-км", m, y, Cat.BUS, a -> a.pkm / 1000));
        rows.add(group("3. Троллейбусҳо"));
        rows.add(row("05", "мусофир кашонида шудааст", "ҳазор мусофир", m, y, Cat.TROLLEY, a -> a.passengers / 1000));
        rows.add(row("06", "гардиши мусофирон", "ҳазор мусофир-км", m, y, Cat.TROLLEY, a -> a.pkm / 1000));
        // В бланке «Роҳхат» у такси те же рамзҳо 05/06, что у троллейбусов (ошибка шаблона) — рамз не ставим.
        rows.add(group("4. Таксомоторҳои сабукрав"));
        rows.add(row("", "мусофир кашонида шудааст", "ҳазор мусофир", m, y, Cat.TAXI, a -> a.passengers / 1000));
        rows.add(row("", "гардиши мусофирон", "ҳазор мусофир-км", m, y, Cat.TAXI, a -> a.pkm / 1000));

        block(rows, "07", "Вақти ба ихтиёри хоҷагӣ ҳозиршавии сохтори ҳаракаткунанда", "автомобил-шабонарӯз",
                m, y, a -> a.fleet);
        block(rows, "12", "Вақти ҳозиршавии сохтори ҳаракаткунанда ба кор", "автомобил-шабонарӯз",
                m, y, a -> a.vehicleDays.size());
        block(rows, "17", "Вақти ҳозиршавии сохтори ҳаракаткунанда ба кор", "соат",
                m, y, a -> a.minutes / 60d);
        block(rows, "22", "Гашти сохтори ҳаракаткунанда", "ҳазор км",
                m, y, a -> a.totalKm / 1000);
        block(rows, "27", "Гашти сохтори ҳаракаткунанда бо бор, мусофир, гашти пулакӣ", "ҳазор км",
                m, y, a -> a.paidKm / 1000);
        block(rows, "40", "Шумораи мошинҳо", "адад",
                m, y, a -> a.vehicles.size());
        return rows;
    }

    /** Итоговая строка и разбивка (боркашҳо, автобусҳо, таксомоторҳо, троллейбусҳо) — рамзҳо подряд. */
    private static void block(List<Row> rows, String code, String label, String unit,
                              Map<Cat, Acc> m, Map<Cat, Acc> y, Metric metric) {
        int c = Integer.parseInt(code);
        rows.add(new Row(code, label, unit, sum(m, metric), y == null ? null : sum(y, metric), 0));
        Cat[] order = {Cat.TRUCK, Cat.BUS, Cat.TAXI, Cat.TROLLEY};
        String[] names = {"аз он ҷумла, боркашҳо", "автобусҳо", "таксомоторҳои сабукрав", "троллейбусҳо"};
        for (int i = 0; i < order.length; i++) {
            rows.add(row(String.format("%02d", c + 1 + i), names[i], unit, m, y, order[i], metric));
        }
    }

    private static Row group(String label) {
        return new Row(null, label, null, null, null, 0);
    }

    private static Row row(String code, String label, String unit, Map<Cat, Acc> m, Map<Cat, Acc> y,
                           Cat cat, Metric metric) {
        return new Row(code, label, unit, round(metric.of(m.get(cat))),
                y == null ? null : round(metric.of(y.get(cat))), 1);
    }

    private static Double sum(Map<Cat, Acc> acc, Metric metric) {
        double s = 0;
        for (Acc a : acc.values()) {
            s += metric.of(a);
        }
        return round(s);
    }

    private static Double round(double v) {
        return Math.round(v * 100d) / 100d;
    }

    private static String norm(String s) {
        return s == null ? "—" : s.replace(" ", "").trim().toUpperCase();
    }

    /** Область организаций — как у типовых отчётов ({@link WaybillReportService}). */
    private Set<String> resolveScope(String requested) {
        if (tenantScope.isBounded()) {
            Set<String> s = tenantScope.rmas();
            if (s == null || s.contains("__none__")) {
                return Set.of();
            }
            if (requested != null && !requested.isBlank() && s.contains(requested.trim())) {
                return Set.of(requested.trim());
            }
            return s;
        }
        return requested == null || requested.isBlank() ? null : Set.of(requested.trim());
    }
}
