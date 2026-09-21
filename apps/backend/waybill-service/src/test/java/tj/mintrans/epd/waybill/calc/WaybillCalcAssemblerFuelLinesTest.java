package tj.mintrans.epd.waybill.calc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.calc.model.CalcFuelLine;
import tj.mintrans.epd.waybill.domain.FuelRecord;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * MIGRATION.md §5.6 — записи топлива ПЛ должны попадать в расчёт со ВСЕМИ полями строки legacy
 * ({@code fuels[].fuel_given / coef_below_0 / additional / remain_fuel_before_exit}), а не только
 * с выданным и остатком до выезда. До 21.09 coef_below_0 и additional подавались нулями.
 */
class WaybillCalcAssemblerFuelLinesTest {

    private static final double EPS = 1e-9;

    private static FuelRecord record(int fuelType, String given, String coefBelow0, String additional,
                                     String remainBefore, String beGiven) {
        FuelRecord fr = new FuelRecord();
        fr.setFuelType((short) fuelType);
        fr.setFuelGiven(given == null ? null : new BigDecimal(given));
        fr.setCoefBelow0(coefBelow0 == null ? null : new BigDecimal(coefBelow0));
        fr.setAdditionalGiven(additional == null ? null : new BigDecimal(additional));
        fr.setRemainBeforeExit(remainBefore == null ? null : new BigDecimal(remainBefore));
        fr.setBeGiven(beGiven == null ? null : new BigDecimal(beGiven));
        return fr;
    }

    @Test
    @DisplayName("coef_below_0 и additional передаются в CalcFuelLine (legacy fuels[] 1-в-1)")
    void mapsAllLegacyFields() {
        List<CalcFuelLine> lines = WaybillCalcAssembler.toCalcFuelLines(List.of(
                record(2, "80", "2", "3", "10", "85"),
                record(3, "15.5", null, null, "0.5", null)));

        assertThat(lines).hasSize(2);
        CalcFuelLine diesel = lines.get(0);
        assertThat(diesel.fuelId()).isEqualTo(2L);
        assertThat(diesel.fuelGiven()).isCloseTo(80d, within(EPS));
        assertThat(diesel.coefBelow0()).isCloseTo(2d, within(EPS));       // раньше — 0
        assertThat(diesel.additional()).isCloseTo(3d, within(EPS));       // раньше — 0
        assertThat(diesel.remainFuelBeforeExit()).isCloseTo(10d, within(EPS));

        CalcFuelLine gas = lines.get(1);
        assertThat(gas.fuelId()).isEqualTo(3L);
        assertThat(gas.fuelGiven()).isCloseTo(15.5d, within(EPS));
        assertThat(gas.coefBelow0()).isZero();
        assertThat(gas.additional()).isZero();
        assertThat(gas.remainFuelBeforeExit()).isCloseTo(0.5d, within(EPS));
    }

    @Test
    @DisplayName("пустые/null записи → пустой список и нули, без NPE")
    void nullSafe() {
        assertThat(WaybillCalcAssembler.toCalcFuelLines(null)).isEmpty();
        List<CalcFuelLine> lines = WaybillCalcAssembler.toCalcFuelLines(List.of(record(1, null, null, null, null, null)));
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).fuelGiven()).isZero();
        assertThat(lines.get(0).coefBelow0()).isZero();
        assertThat(lines.get(0).additional()).isZero();
    }
}
