package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.calc.model.FuelConsumption;
import tj.mintrans.epd.waybill.calc.report.ReportRow;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Топливо по видам Б/С/Г в типовых отчётах (legacy «меъёр / асл Б/С/Г»; сверка 25.09, D6). */
class WaybillReportFuelSplitTest {

    @Test
    @DisplayName("бензин — 1, солярка — 2, газ — 3 и 4; электроэнергия (5) в Б/С/Г не входит")
    void split() {
        var s = WaybillReportService.fuelSplit(List.of(
                new FuelConsumption(1, 10, 0, 8, 0, 0),
                new FuelConsumption(2, 40, 0, 32, 0, 0),
                new FuelConsumption(3, 5, 0, 6, 0, 0),
                new FuelConsumption(4, 2, 0, 1, 0, 0),
                new FuelConsumption(5, 100, 0, 90, 0, 0)));
        assertThat(s).isEqualTo(new ReportRow.FuelSplit(8, 32, 7, 10, 40, 7));
    }

    @Test
    @DisplayName("строка отчёта суммирует виды по листам и в «ИТОГО»")
    void accumulate() {
        var row = ReportRow.zero("A", "A")
                .plus(0, 0, 0, 0, 0, 40, 50, null, null, null, 0, 0, 0, 0, new ReportRow.FuelSplit(0, 40, 0, 0, 50, 0))
                .plus(0, 0, 0, 0, 0, 10, 8, null, null, null, 0, 0, 0, 0, new ReportRow.FuelSplit(10, 0, 0, 8, 0, 0));
        assertThat(row.fuelNormDiesel()).isEqualTo(40);
        assertThat(row.fuelGivenPetrol()).isEqualTo(8);
        var total = ReportRow.zero("T", "T").merge(row).merge(row);
        assertThat(total.fuelGivenDiesel()).isEqualTo(100);
        assertThat(total.fuelNormPetrol()).isEqualTo(20);
    }
}
