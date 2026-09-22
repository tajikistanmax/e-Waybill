package tj.mintrans.epd.waybill.calc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.calc.model.BrandNorms;
import tj.mintrans.epd.waybill.client.MasterDataClient;
import tj.mintrans.epd.waybill.domain.FuelRecord;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.domain.WorkDay;
import tj.mintrans.epd.waybill.repository.FuelRecordRepository;
import tj.mintrans.epd.waybill.repository.WorkDayRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIGRATION.md §5.4 (B10) — многодневный 1-А: топливо по строкам, привязанным к рабочим дням, считается
 * посуточно ({@code MBusTrait::calcFuel}) и замещает свод движка; без привязки к дням — прежний расчёт.
 */
class WaybillCalcAssemblerDailyFuelTest {

    private FuelRecordRepository fuelRecords;
    private WorkDayRepository workDays;
    private WaybillCalcAssembler assembler;
    private final UUID day1 = UUID.randomUUID();
    private final UUID day2 = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        CoefficientDictionaries dict = mock(CoefficientDictionaries.class);
        lenient().when(dict.winterCoef(anyLong())).thenReturn(Optional.empty());
        lenient().when(dict.mountainCoef(anyLong())).thenReturn(Optional.empty());
        lenient().when(dict.cityCoef(anyLong())).thenReturn(Optional.empty());
        lenient().when(dict.usedCoefRows()).thenReturn(List.of());
        lenient().when(dict.driveClasses()).thenReturn(List.of());
        BrandNormsProvider brandNorms = mock(BrandNormsProvider.class);
        lenient().when(brandNorms.forName(any())).thenReturn(new BrandNorms(1L,
                "[{\"fuel_id\":1,\"consumption\":10}]", null, null, 0d));
        MasterDataClient masterData = mock(MasterDataClient.class);
        lenient().when(masterData.findRoute(any())).thenReturn(Optional.empty());   // маршрут не задан → множитель 1
        fuelRecords = mock(FuelRecordRepository.class);
        workDays = mock(WorkDayRepository.class);
        WorkDay d1 = day(day1, LocalDate.of(2026, 9, 1), 1000, 1120);
        WorkDay d2 = day(day2, LocalDate.of(2026, 9, 2), 1120, 1170);
        lenient().when(workDays.findByWaybillIdOrderByWorkDate(any())).thenReturn(List.of(d1, d2));
        lenient().when(workDays.countByWaybillId(any())).thenReturn(2L);
        WaybillCalcEngine engine = new WaybillCalcEngine(new CoefficientCalculator(dict), new FuelNormCalculator(), brandNorms);
        assembler = new WaybillCalcAssembler(engine, masterData, workDays, fuelRecords);
    }

    private static WorkDay day(UUID id, LocalDate date, int exit, int entry) {
        WorkDay d = mock(WorkDay.class);
        when(d.getId()).thenReturn(id);
        when(d.getWorkDate()).thenReturn(date);
        when(d.getOdometerExit()).thenReturn(exit);
        when(d.getOdometerEntry()).thenReturn(entry);
        lenient().when(d.getExitTime()).thenReturn(LocalTime.of(8, 0));
        lenient().when(d.getEntryTime()).thenReturn(LocalTime.of(18, 0));
        return d;
    }

    private static Waybill minibus() {
        Waybill wb = new Waybill();
        wb.setWaybillType(WaybillType.WB_MINIBUS);
        wb.setVehicleRegNumber("2200TJ02");
        Map<String, Object> veh = new HashMap<>();
        veh.put("brand", "Sprinter");
        veh.put("yearManufacture", 2020);
        veh.put("capacity", 18);
        wb.setVehicleSnapshot(veh);
        wb.setOrganizationSnapshot(Map.of("regionId", 3));
        wb.setTypeData(new HashMap<>());
        return wb;
    }

    private static FuelRecord fuel(UUID dayId, String given, String remainBefore) {
        FuelRecord f = new FuelRecord();
        f.setFuelType((short) 1);
        f.setWorkDayId(dayId);
        f.setFuelGiven(new BigDecimal(given));
        if (remainBefore != null) f.setRemainBeforeExit(new BigDecimal(remainBefore));
        return f;
    }

    @Test
    @DisplayName("строки топлива по дням: день 1 — 120 км, 20+5 л → расход 12, остаток 13; день 2 — 50 км, остаток цепочкой → расход 5, остаток 8; свод 17 л")
    void perDayFuelReplacesEngineTotals() {
        when(fuelRecords.findByWaybillIdOrderByCreatedAt(any())).thenReturn(List.of(
                fuel(day1, "20", "5"), fuel(day2, "0", null)));

        WaybillCalcAssembler.View v = assembler.calculate(minibus(), WaybillCalcAssembler.Supplement.empty());

        assertThat(v.kind()).isEqualTo("PASSENGER");
        assertThat(v.dailyFuel()).hasSize(2);
        var d1 = v.dailyFuel().get(0).lines().getFirst();
        assertThat(d1.consumption()).isCloseTo(12d, within(1e-9));          // 120 км × 10 л/100 × 1.0
        assertThat(d1.remainEntry()).isCloseTo(13d, within(1e-9));          // 20 + 5 − 12
        var d2 = v.dailyFuel().get(1).lines().getFirst();
        assertThat(d2.remainBeforeExit()).isCloseTo(13d, within(1e-9));     // цепочка от дня 1
        assertThat(d2.consumption()).isCloseTo(5d, within(1e-9));
        assertThat(d2.remainEntry()).isCloseTo(8d, within(1e-9));
        assertThat(v.passenger().fuels()).hasSize(1);
        assertThat(v.passenger().fuels().getFirst().normLiters()).isCloseTo(17d, within(1e-9));
        assertThat(v.passenger().fuels().getFirst().remainEntry()).isCloseTo(8d, within(1e-9));
        assertThat(v.passenger().totalNormLiters()).isCloseTo(17d, within(1e-9));
        assertThat(v.notes()).anyMatch(n -> n.contains("посуточно по строкам рабочих дней"));
    }

    @Test
    @DisplayName("строки топлива без привязки к дням — посуточный расчёт не применяется (движок по листу целиком)")
    void withoutDayLinksEngineTotalsStay() {
        when(fuelRecords.findByWaybillIdOrderByCreatedAt(any())).thenReturn(List.of(fuel(null, "20", "5")));

        WaybillCalcAssembler.View v = assembler.calculate(minibus(), WaybillCalcAssembler.Supplement.empty());

        assertThat(v.dailyFuel()).isEmpty();
        // движок: 170 км × 10 л/100 × 1.0 = 17 л, остаток 5 + 20 − 17 = 8
        assertThat(v.passenger().totalNormLiters()).isCloseTo(17d, within(1e-9));
        assertThat(v.notes()).noneMatch(n -> n.contains("по строкам рабочих дней"));
    }
}
