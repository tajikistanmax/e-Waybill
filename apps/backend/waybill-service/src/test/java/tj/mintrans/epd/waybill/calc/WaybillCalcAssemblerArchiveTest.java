package tj.mintrans.epd.waybill.calc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.calc.model.BrandNorms;
import tj.mintrans.epd.waybill.calc.model.PassengerMetrics;
import tj.mintrans.epd.waybill.client.MasterDataClient;
import tj.mintrans.epd.waybill.domain.FuelRecord;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Архивный лист, перенесённый из legacy фазами 5/5b (находка 11 AUDIT.md): снимок ТС без вместимости,
 * показатели работы — в {@code work_day}/{@code fuel_record}, у марки в справочнике нет нормативов.
 * Отчёт должен получить рейсы, выручку, пассажирооборот (по вместимости марки, как legacy
 * {@code parking->brand->capacity}) и выданное топливо, а не нули.
 */
class WaybillCalcAssemblerArchiveTest {

    private MasterDataClient masterData;
    private WorkDayRepository workDays;
    private FuelRecordRepository fuelRecords;
    private WaybillCalcAssembler assembler;

    @BeforeEach
    void setUp() {
        CoefficientDictionaries dict = mock(CoefficientDictionaries.class);
        lenient().when(dict.winterCoef(anyLong())).thenReturn(Optional.empty());
        lenient().when(dict.mountainCoef(anyLong())).thenReturn(Optional.empty());
        lenient().when(dict.cityCoef(anyLong())).thenReturn(Optional.empty());
        lenient().when(dict.usedCoefRows()).thenReturn(List.of());
        lenient().when(dict.driveClasses()).thenReturn(List.of());
        BrandNormsProvider brandNorms = mock(BrandNormsProvider.class);
        // марка найдена, но таблица нормативов fuel_100 пустая (так у большинства марок справочника)
        lenient().when(brandNorms.forName(any())).thenReturn(new BrandNorms(7L, null, null, null, 0d));

        masterData = mock(MasterDataClient.class);
        Map<String, Object> route = new HashMap<>();
        route.put("name", "Автовокзал - Зарафшон");
        route.put("distanceA", 10);
        route.put("distanceB", 10);
        route.put("plannedLap", 8);
        route.put("coeUseCapacity", 0.5);
        route.put("averageLengthPassSeat", 5);
        lenient().when(masterData.findRoute(eq("Автовокзал - Зарафшон"))).thenReturn(Optional.of(route));
        lenient().when(masterData.findRoute(eq("Автовокзал - Зарафшон"), any())).thenReturn(Optional.of(route));
        lenient().when(masterData.findBrandByName(eq("ЛиАЗ-5256"))).thenReturn(
                Optional.of(Map.of("name", "ЛиАЗ-5256", "capacity", 80)));

        workDays = mock(WorkDayRepository.class);
        fuelRecords = mock(FuelRecordRepository.class);
        WaybillCalcEngine engine = new WaybillCalcEngine(new CoefficientCalculator(dict), new FuelNormCalculator(), brandNorms);
        assembler = new WaybillCalcAssembler(engine, masterData, workDays, fuelRecords);
    }

    /** Лист 1-АД из архива: снимки только с тем, что несёт legacy-шапка (без вместимости, региона, доли дохода). */
    private static Waybill archivedBus() {
        Waybill wb = new Waybill();
        wb.setWaybillType(WaybillType.WB_BUS);
        wb.setStatus(WaybillStatus.ARCHIVED);
        wb.setSource("MIGRATED");
        wb.setVehicleRegNumber("2409TT05");
        wb.setRoute("Автовокзал - Зарафшон");
        wb.setOdometerExit(1000);
        wb.setOdometerEntry(1200);
        wb.setVehicleSnapshot(new HashMap<>(Map.of("registrationNumber", "2409TT05", "brand", "ЛиАЗ-5256", "migrated", true)));
        wb.setOrganizationSnapshot(new HashMap<>(Map.of("rma", "040000796", "name", "Автобуси 2", "migrated", true)));
        wb.setDriverSnapshot(new HashMap<>(Map.of("rma", "075374266", "migrated", true)));
        wb.setTypeData(new HashMap<>(Map.of("migrated", true, "legacyTable", "1D", "legacyId", "580501")));
        return wb;
    }

    private void oneWorkDay(int laps, String revenue) {
        WorkDay d = mock(WorkDay.class);
        lenient().when(d.getWorkDate()).thenReturn(LocalDate.of(2026, 7, 15));
        lenient().when(d.getExitTime()).thenReturn(LocalTime.of(6, 0));
        lenient().when(d.getEntryTime()).thenReturn(LocalTime.of(20, 0));
        lenient().when(d.getLaps()).thenReturn(laps);
        lenient().when(d.getRevenue()).thenReturn(new BigDecimal(revenue));
        when(workDays.findByWaybillIdOrderByWorkDate(any())).thenReturn(List.of(d));
        lenient().when(workDays.countByWaybillId(any())).thenReturn(1L);
    }

    @Test
    @DisplayName("рейсы/выручка из work_day, пассажирооборот по вместимости марки 80: 80·0.5·10·5 = 2000, пассажиров 400")
    void archivedWaybillMetricsFromWorkDayAndBrandCapacity() {
        oneWorkDay(5, "1000.00");
        FuelRecord f = new FuelRecord();
        f.setFuelType((short) 2);
        f.setFuelGiven(new BigDecimal("100"));
        f.setRemainBeforeExit(new BigDecimal("10"));
        when(fuelRecords.findByWaybillIdOrderByCreatedAt(any())).thenReturn(List.of(f));

        WaybillCalcAssembler.View v = assembler.calculate(archivedBus(), WaybillCalcAssembler.Supplement.empty());

        assertThat(v.kind()).isEqualTo("PASSENGER");
        PassengerMetrics m = v.passenger().passengerMetrics();
        assertThat(m.laps()).isEqualTo(5);
        assertThat(m.earning()).isEqualByComparingTo("1000.00");
        assertThat(m.kassa()).isEqualByComparingTo("1000.00");
        assertThat(m.passengerTurnover()).isCloseTo(2000d, within(1e-9));
        assertThat(m.passengerCount()).isCloseTo(400d, within(1e-9));
        assertThat(v.notes()).anyMatch(n -> n.contains("Вместимость взята из справочника марок: 80"));
        // выданное топливо показано и без нормативов марки
        assertThat(v.passenger().fuels()).hasSize(1);
        assertThat(v.passenger().fuels().getFirst().given()).isCloseTo(100d, within(1e-9));
        assertThat(v.passenger().fuels().getFirst().normLiters()).isZero();
        assertThat(v.passenger().fuels().getFirst().remainEntry()).isEqualTo(110d);
    }

    @Test
    @DisplayName("маршрут архивного листа не найден в справочнике: рейсы и выручка всё равно в показателях")
    void routeNotFoundKeepsLapsAndRevenue() {
        oneWorkDay(7, "2500.50");
        when(fuelRecords.findByWaybillIdOrderByCreatedAt(any())).thenReturn(List.of());
        Waybill wb = archivedBus();
        wb.setRoute("Маршрут, которого нет в справочнике");

        WaybillCalcAssembler.View v = assembler.calculate(wb, WaybillCalcAssembler.Supplement.empty());

        PassengerMetrics m = v.passenger().passengerMetrics();
        assertThat(m.laps()).isEqualTo(7);
        assertThat(m.earning()).isEqualByComparingTo("2500.50");
        assertThat(m.kassa()).isEqualByComparingTo("2500.50");
        assertThat(m.passengerTurnover()).isZero();
    }

    @Test
    @DisplayName("доля дохода и регион архивной организации — из справочника (как legacy company->percent_income): "
            + "заработок 1000/4·3·0.33 = 247.50; Душанбе → общий пробег по одометру 200 км")
    void archivedOrganizationFieldsFromDirectory() {
        oneWorkDay(5, "1000.00");
        when(fuelRecords.findByWaybillIdOrderByCreatedAt(any())).thenReturn(List.of());
        when(masterData.listOrganizations()).thenReturn(List.of(
                Map.of("rma", "040000796", "percentIncome", 0.33, "cat1", 100, "cat2", 40, "cat3", 0, "regionId", 1)));
        Waybill wb = archivedBus();
        wb.setOrganizationRma("040000796");

        WaybillCalcAssembler.View v = assembler.calculate(wb, WaybillCalcAssembler.Supplement.empty());

        // разряд водителя в архивном снимке не перенесён → надбавка за класс 0
        assertThat(v.passenger().salary().salary()).isEqualByComparingTo("247.50");
        assertThat(v.passenger().passengerMetrics().speedometerBased()).isTrue();
        assertThat(v.passenger().passengerMetrics().totalDistanceKm()).isCloseTo(200d, within(1e-9));
    }

    @Test
    @DisplayName("живой лист (снимок без migrated) справочник организаций не использует")
    void liveWaybillIgnoresDirectory() {
        oneWorkDay(5, "1000.00");
        when(fuelRecords.findByWaybillIdOrderByCreatedAt(any())).thenReturn(List.of());
        lenient().when(masterData.listOrganizations()).thenReturn(List.of(
                Map.of("rma", "040000796", "percentIncome", 0.33, "regionId", 1)));
        Waybill wb = archivedBus();
        wb.setOrganizationRma("040000796");
        wb.setOrganizationSnapshot(new HashMap<>(Map.of("rma", "040000796", "name", "Автобуси 2")));

        WaybillCalcAssembler.View v = assembler.calculate(wb, WaybillCalcAssembler.Supplement.empty());

        assertThat(v.passenger().salary().salary()).isEqualByComparingTo("0.00");
        assertThat(v.passenger().passengerMetrics().speedometerBased()).isFalse();
    }

    @Test
    @DisplayName("вместимость в снимке есть — справочник марок не опрашивается")
    void snapshotCapacityWins() {
        oneWorkDay(5, "0");
        when(fuelRecords.findByWaybillIdOrderByCreatedAt(any())).thenReturn(List.of());
        Waybill wb = archivedBus();
        wb.getVehicleSnapshot().put("capacity", 40);

        WaybillCalcAssembler.View v = assembler.calculate(wb, WaybillCalcAssembler.Supplement.empty());

        assertThat(v.passenger().passengerMetrics().passengerTurnover()).isCloseTo(1000d, within(1e-9)); // 40·0.5·10·5
        assertThat(v.notes()).noneMatch(n -> n.contains("Вместимость взята из справочника марок"));
    }
}
