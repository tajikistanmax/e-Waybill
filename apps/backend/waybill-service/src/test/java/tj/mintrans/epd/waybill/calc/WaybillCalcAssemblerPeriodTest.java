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
 * Сверка 25.09, D3 — многодневный 1-А в отчёте за период: дни периода, посуточное топливо дней периода;
 * величины листа целиком — в периоде первого рабочего дня. Лист с днями 31.08 и 01.09.
 */
class WaybillCalcAssemblerPeriodTest {

    private static final WaybillCalcAssembler.Period AUGUST =
            new WaybillCalcAssembler.Period(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
    private static final WaybillCalcAssembler.Period SEPTEMBER =
            new WaybillCalcAssembler.Period(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
    private static final WaybillCalcAssembler.Period OCTOBER =
            new WaybillCalcAssembler.Period(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

    private FuelRecordRepository fuelRecords;
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
        lenient().when(masterData.findRoute(any())).thenReturn(Optional.empty());
        fuelRecords = mock(FuelRecordRepository.class);
        WorkDayRepository workDays = mock(WorkDayRepository.class);
        WorkDay d1 = day(day1, LocalDate.of(2026, 8, 31), 1000, 1120);
        WorkDay d2 = day(day2, LocalDate.of(2026, 9, 1), 1120, 1170);
        lenient().when(workDays.findByWaybillIdOrderByWorkDate(any())).thenReturn(List.of(d1, d2));
        lenient().when(workDays.countByWaybillId(any())).thenReturn(2L);
        WaybillCalcEngine engine = new WaybillCalcEngine(new CoefficientCalculator(dict), new FuelNormCalculator(), brandNorms);
        assembler = new WaybillCalcAssembler(engine, masterData, workDays, fuelRecords);
    }

    private static WorkDay day(UUID id, LocalDate date, int exit, int entry) {
        WorkDay d = mock(WorkDay.class);
        lenient().when(d.getId()).thenReturn(id);
        lenient().when(d.getWorkDate()).thenReturn(date);
        lenient().when(d.getOdometerExit()).thenReturn(exit);
        lenient().when(d.getOdometerEntry()).thenReturn(entry);
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
    @DisplayName("посуточное топливо: август — день 31.08 (12 л), сентябрь — день 01.09 (5 л); вне дней — пусто")
    void dayFuelFollowsDays() {
        when(fuelRecords.findByWaybillIdOrderByCreatedAt(any())).thenReturn(List.of(
                fuel(day1, "20", "5"), fuel(day2, "0", null)));

        var aug = assembler.calculate(minibus(), WaybillCalcAssembler.Supplement.empty(), AUGUST);
        assertThat(aug.outOfPeriod()).isFalse();
        assertThat(aug.workDays()).isEqualTo(1);
        assertThat(aug.passenger().totalNormLiters()).isCloseTo(12d, within(1e-9));

        var sep = assembler.calculate(minibus(), WaybillCalcAssembler.Supplement.empty(), SEPTEMBER);
        assertThat(sep.outOfPeriod()).isFalse();
        assertThat(sep.workDays()).isEqualTo(1);
        assertThat(sep.workMinutes()).isEqualTo(600);
        assertThat(sep.passenger().totalNormLiters()).isCloseTo(5d, within(1e-9));
        assertThat(sep.passenger().salary().salary()).isEqualByComparingTo("0");   // надбавки — в периоде 1-го дня

        var oct = assembler.calculate(minibus(), WaybillCalcAssembler.Supplement.empty(), OCTOBER);
        assertThat(oct.outOfPeriod()).isTrue();

        // Без периода (карточка ПЛ) — весь лист, как раньше.
        var all = assembler.calculate(minibus(), WaybillCalcAssembler.Supplement.empty());
        assertThat(all.passenger().totalNormLiters()).isCloseTo(17d, within(1e-9));
        assertThat(all.workDays()).isEqualTo(2);
    }

    @Test
    @DisplayName("топливо без привязки к дням — целиком в периоде первого рабочего дня, не дважды")
    void waybillLevelFuelOnce() {
        when(fuelRecords.findByWaybillIdOrderByCreatedAt(any())).thenReturn(List.of(fuel(null, "20", "5")));

        var aug = assembler.calculate(minibus(), WaybillCalcAssembler.Supplement.empty(), AUGUST);
        assertThat(aug.passenger().totalNormLiters()).isCloseTo(17d, within(1e-9));

        var sep = assembler.calculate(minibus(), WaybillCalcAssembler.Supplement.empty(), SEPTEMBER);
        assertThat(sep.passenger().totalNormLiters()).isCloseTo(0d, within(1e-9));
        assertThat(sep.passenger().fuels()).isEmpty();
        assertThat(sep.workDays()).isEqualTo(1);
    }

    @Test
    @DisplayName("автобус (не 1-А/3-С) — период не применяется")
    void busIgnoresPeriod() {
        Waybill bus = minibus();
        bus.setWaybillType(WaybillType.WB_BUS);
        when(fuelRecords.findByWaybillIdOrderByCreatedAt(any())).thenReturn(List.of(fuel(null, "20", "5")));
        var oct = assembler.calculate(bus, WaybillCalcAssembler.Supplement.empty(), OCTOBER);
        assertThat(oct.outOfPeriod()).isFalse();
        assertThat(oct.passenger().totalNormLiters()).isCloseTo(17d, within(1e-9));
    }
}
