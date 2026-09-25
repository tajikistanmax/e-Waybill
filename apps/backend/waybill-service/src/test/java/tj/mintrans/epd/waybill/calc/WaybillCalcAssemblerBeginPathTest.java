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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * «Гашти ибтидоӣ» (сверка 25.09.2026, legacy BusBaseCalc:40-72): нулевой пробег — по селекторам листа
 * (route->{begin_path_a} + route->{begin_path_b}), а норма топлива автобуса вне Душанбе — от
 * «гашти ҳамагӣ» (пробег по маршруту + нулевой), а не от одометра.
 */
class WaybillCalcAssemblerBeginPathTest {

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
        lenient().when(brandNorms.forName(any())).thenReturn(new BrandNorms(1L,
                "[{\"fuel_id\":2,\"consumption\":30}]", null, null, 0d));
        MasterDataClient masterData = mock(MasterDataClient.class);
        Map<String, Object> route = new HashMap<>();
        route.put("id", "r1");
        route.put("distanceA", 10);
        route.put("distanceB", 10);
        route.put("beginPathA", 2);
        route.put("beginPathB", 5);
        route.put("plannedLap", 6);
        route.put("coeUseCapacity", 0.5);
        route.put("averageLengthPassSeat", 5);
        lenient().when(masterData.findRoute(anyString(), anyString())).thenReturn(Optional.of(route));
        lenient().when(masterData.listRouteTariffs(anyString())).thenReturn(List.of());
        workDays = mock(WorkDayRepository.class);
        fuelRecords = mock(FuelRecordRepository.class);
        FuelRecord diesel = new FuelRecord();
        diesel.setFuelType((short) 2);
        diesel.setFuelGiven(new BigDecimal("40"));
        diesel.setRemainBeforeExit(new BigDecimal("10"));
        lenient().when(fuelRecords.findByWaybillIdOrderByCreatedAt(any())).thenReturn(List.of(diesel));
        WaybillCalcEngine engine = new WaybillCalcEngine(new CoefficientCalculator(dict), new FuelNormCalculator(), brandNorms);
        assembler = new WaybillCalcAssembler(engine, masterData, workDays, fuelRecords);
    }

    private void day(String a, String b) {
        WorkDay d = new WorkDay();
        d.setWorkDate(LocalDate.of(2026, 9, 20));
        d.setExitTime(LocalTime.of(6, 0));
        d.setEntryTime(LocalTime.of(18, 0));
        d.setOdometerExit(1000);
        d.setOdometerEntry(1100);
        d.setLaps(4);
        d.setBeginPathA(a);
        d.setBeginPathB(b);
        when(workDays.findByWaybillIdOrderByWorkDate(any())).thenReturn(List.of(d));
        when(workDays.countByWaybillId(any())).thenReturn(1L);
    }

    private static Waybill bus(long regionId) {
        Waybill wb = new Waybill();
        wb.setWaybillType(WaybillType.WB_BUS);
        wb.setRoute("12");
        wb.setOrganizationRma("025680800");
        wb.setOdometerExit(1000);
        wb.setOdometerEntry(1100);
        Map<String, Object> veh = new HashMap<>();
        veh.put("brand", "ПАЗ");
        veh.put("yearManufacture", 2022);
        veh.put("capacity", 40);
        wb.setVehicleSnapshot(veh);
        wb.setOrganizationSnapshot(Map.of("regionId", regionId));
        wb.setTypeData(new HashMap<>());
        return wb;
    }

    @Test
    @DisplayName("вне Душанбе, селекторы А+А: гашти ҳамагӣ = 10·4 + 2 + 2 = 44 км, норма 30 л/100 × 44 = 13.2 л (не 30 л по одометру)")
    void selectorsAAndNormFromRouteRun() {
        day("begin_path_a", "begin_path_a");
        var v = assembler.calculate(bus(3), WaybillCalcAssembler.Supplement.empty());
        assertThat(v.passenger().passengerMetrics().totalDistanceKm()).isCloseTo(44d, within(1e-9));
        assertThat(v.passenger().totalNormLiters()).isCloseTo(13.2d, within(1e-6));
        // остаток после возврата: 10 + 40 − 13.2
        assertThat(v.passenger().fuels().getFirst().remainEntry()).isCloseTo(36.8d, within(1e-6));
    }

    @Test
    @DisplayName("без селекторов (архив) — прежнее правило А + Б: 40 + 2 + 5 = 47 км")
    void noSelectorsKeepsAPlusB() {
        day(null, null);
        var v = assembler.calculate(bus(3), WaybillCalcAssembler.Supplement.empty());
        assertThat(v.passenger().passengerMetrics().totalDistanceKm()).isCloseTo(47d, within(1e-9));
        assertThat(v.passenger().totalNormLiters()).isCloseTo(14.1d, within(1e-6));
    }

    @Test
    @DisplayName("Душанбе (регион 1): гашти ҳамагӣ и норма — по одометру, 100 км → 30 л")
    void dushanbeUsesOdometer() {
        day("begin_path_a", null);
        var v = assembler.calculate(bus(1), WaybillCalcAssembler.Supplement.empty());
        assertThat(v.passenger().passengerMetrics().totalDistanceKm()).isCloseTo(100d, within(1e-9));
        assertThat(v.passenger().totalNormLiters()).isCloseTo(30d, within(1e-6));
    }
}
