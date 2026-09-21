package tj.mintrans.epd.waybill.calc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.calc.model.BrandNorms;
import tj.mintrans.epd.waybill.calc.model.SimpleCoefRef;
import tj.mintrans.epd.waybill.calc.model.WinterCoefRef;
import tj.mintrans.epd.waybill.client.MasterDataClient;
import tj.mintrans.epd.waybill.domain.FuelRecord;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.repository.FuelRecordRepository;
import tj.mintrans.epd.waybill.repository.WorkDayRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * MIGRATION.md §5.2 — живой грузовой расчёт (2-Б) по данным самого ПЛ, без Supplement, как в оригинале:
 * коэффициент {@code K = winter + mountain + city + used} берётся из справочника Direction по
 * {@code typeData.directionId} ({@code CargoFuelBase::getCoef}), надбавка за прицеп — из снимка ТС
 * ({@code parkings.weight_ydak / carrying_ydak}). До 21.09 без Supplement оба источника игнорировались.
 */
class WaybillCalcAssemblerCargoTest {

    private MasterDataClient masterData;
    private FuelRecordRepository fuelRecords;
    private WorkDayRepository workDays;
    private WaybillCalcAssembler assembler;

    @BeforeEach
    void setUp() {
        CoefficientDictionaries dict = mock(CoefficientDictionaries.class);
        lenient().when(dict.winterCoef(anyLong())).thenReturn(Optional.empty());
        lenient().when(dict.mountainCoef(anyLong())).thenReturn(Optional.empty());
        lenient().when(dict.cityCoef(anyLong())).thenReturn(Optional.empty());
        lenient().when(dict.usedCoefRows()).thenReturn(List.of());
        lenient().when(dict.driveClasses()).thenReturn(List.of());
        // Справочники направления: зима id=1 (ноябрь–март, 5 %), горы id=2 (10 %), город id=3 (10 %).
        lenient().when(dict.winterCoef(1L)).thenReturn(Optional.of(
                new WinterCoefRef(1L, LocalDate.of(2000, 11, 1), LocalDate.of(2001, 3, 1), 5)));
        lenient().when(dict.mountainCoef(2L)).thenReturn(Optional.of(new SimpleCoefRef(2L, 10)));
        lenient().when(dict.cityCoef(3L)).thenReturn(Optional.of(new SimpleCoefRef(3L, 10)));

        BrandNormsProvider brandNorms = mock(BrandNormsProvider.class);
        // КамАЗ бортовой: 25 л/100 км, 1.3 л на 100 т·км (brands.fuel_100 JSON)
        lenient().when(brandNorms.forName(any())).thenReturn(new BrandNorms(1L,
                "[{\"fuel_id\":2,\"consumption\":25,\"ton_for_100\":1.3}]", null, null, 0d));

        masterData = mock(MasterDataClient.class);
        lenient().when(masterData.findRoute(any())).thenReturn(Optional.empty());
        // Код марки 10001: кузов '1' (бортовой), 5-й символ '1' (> 0) → есть прицеп.
        lenient().when(masterData.findBrandByName(any())).thenReturn(Optional.of(Map.of("number", "10001")));
        lenient().when(masterData.findDirection(7L)).thenReturn(Optional.of(
                Map.of("id", 7, "title", "Душанбе — Хорог", "winterCoefId", 1, "mountainCoefId", 2, "inCityCoefId", 3)));

        fuelRecords = mock(FuelRecordRepository.class);
        workDays = mock(WorkDayRepository.class);
        lenient().when(workDays.findByWaybillIdOrderByWorkDate(any())).thenReturn(List.of());

        WaybillCalcEngine engine = new WaybillCalcEngine(
                new CoefficientCalculator(dict), new FuelNormCalculator(), brandNorms);
        assembler = new WaybillCalcAssembler(engine, masterData, workDays, fuelRecords);
    }

    private Waybill truck(boolean withDirection, boolean withTrailerInSnapshot) {
        Waybill wb = new Waybill();
        wb.setWaybillType(WaybillType.WB_TRUCK);
        wb.setVehicleRegNumber("1234TT01");
        wb.setValidFrom(OffsetDateTime.of(2026, 1, 15, 8, 0, 0, 0, ZoneOffset.UTC)); // январь → зима
        wb.setOdometerExit(1000);
        wb.setOdometerEntry(1100);   // l_dist = 100 км
        Map<String, Object> veh = new HashMap<>();
        veh.put("brand", "КамАЗ");
        veh.put("yearManufacture", 2020);
        if (withTrailerInSnapshot) {
            veh.put("trailer1Weight", 4.0);
            veh.put("trailer1Carrying", 8.0);
        }
        wb.setVehicleSnapshot(veh);
        wb.setOrganizationSnapshot(Map.of("percentIncome", 0.5, "cat1", 100));
        wb.setDriverSnapshot(Map.of("degree", 1));
        Map<String, Object> td = new HashMap<>();
        if (withDirection) {
            td.put("directionId", 7);
        }
        wb.setTypeData(td);
        FuelRecord fr = new FuelRecord();
        fr.setFuelType((short) 2);
        fr.setFuelGiven(new BigDecimal("100"));
        lenient().when(fuelRecords.findByWaybillIdOrderByCreatedAt(any())).thenReturn(List.of(fr));
        return wb;
    }

    @Test
    @DisplayName("2-Б без Supplement: K = зима 5 + горы 10 + город 10 + износ 0 = 25 из направления ПЛ; прицеп из снимка ТС")
    void directionAndTrailerFromWaybillData() {
        WaybillCalcAssembler.View v = assembler.calculate(truck(true, true), WaybillCalcAssembler.Supplement.empty());

        assertThat(v.kind()).isEqualTo("CARGO");
        var c = v.cargo();
        assertThat(c.coefficients().k()).isEqualTo(25);                     // CargoFuelBase::getCoef
        assertThat(c.coefficients().multiplier()).isCloseTo(1.25d, within(1e-9));
        assertThat(c.hasTrailer()).isTrue();                                // 5-й символ кода марки
        // LabadorFuel: 0.01·(25·100 + 1.3·0)·1.25 = 31.25; + прицеп weight_ydak·l/100·m_tkm = 4·100/100·1.3 = 5.2
        assertThat(c.totalNormLiters()).isCloseTo(36.45d, within(1e-6));
        assertThat(c.fuels().getFirst().remainEntry()).isCloseTo(63.55d, within(1e-6)); // 0 + 100 − 36.45
    }

    @Test
    @DisplayName("без направления и без прицепа в снимке: K = 0 (только износ), надбавки за прицеп нет — прежнее поведение")
    void noDirectionNoTrailer() {
        WaybillCalcAssembler.View v = assembler.calculate(truck(false, false), WaybillCalcAssembler.Supplement.empty());

        var c = v.cargo();
        assertThat(c.coefficients().k()).isZero();
        assertThat(c.totalNormLiters()).isCloseTo(25.0d, within(1e-6));      // 0.01·25·100·1.0, прицеп 0 т
    }

    @Test
    @DisplayName("Supplement переопределяет направление и прицеп (обратная совместимость /calculation с телом)")
    void supplementOverrides() {
        WaybillCalcAssembler.Supplement s = new WaybillCalcAssembler.Supplement(null, null, null, null, null, null, null,
                null, 2L, null, 6.0, 0d, 0d, null, null, null, null, null, null, null, null, null, null);
        WaybillCalcAssembler.View v = assembler.calculate(truck(true, true), s);

        var c = v.cargo();
        assertThat(c.coefficients().k()).isEqualTo(10);                     // только горы из Supplement
        // 0.01·25·100·1.10 = 27.5; + прицеп 6·100/100·1.3 = 7.8
        assertThat(c.totalNormLiters()).isCloseTo(35.3d, within(1e-6));
    }
}
