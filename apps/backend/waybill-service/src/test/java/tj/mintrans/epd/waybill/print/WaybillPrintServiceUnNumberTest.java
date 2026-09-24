package tj.mintrans.epd.waybill.print;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Номер ООН опасного груза на бланке 2-Б и CMR — в виде «UN 1203», как бы его ни ввели. */
class WaybillPrintServiceUnNumberTest {

    @Test
    void normalisesAnyInputToUnPrefix() {
        assertThat(WaybillPrintService.unNumberLabel("1203")).isEqualTo("UN 1203");
        assertThat(WaybillPrintService.unNumberLabel("UN1203")).isEqualTo("UN 1203");
        assertThat(WaybillPrintService.unNumberLabel(" un 1203 ")).isEqualTo("UN 1203");
    }

    @Test
    void emptyIsDash() {
        assertThat(WaybillPrintService.unNumberLabel(null)).isEqualTo("—");
        assertThat(WaybillPrintService.unNumberLabel("  ")).isEqualTo("—");
    }
}
