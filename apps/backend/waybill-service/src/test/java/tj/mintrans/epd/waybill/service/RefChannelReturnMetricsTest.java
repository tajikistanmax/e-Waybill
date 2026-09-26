package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Закрытие листа из B2B-канала КВД (сверка 25.09, A23): круги и выручка 1-АД — показатели дня, не «ездки Z». */
class RefChannelReturnMetricsTest {

    @Test
    @DisplayName("1-АД: number_lap → круги, earning → выручка, гашти ибтидоӣ — селекторы; ездки Z не заполняются")
    void busDaily() {
        var m = RefChannelService.returnMetrics(RefChannelRules.Form.WAYBILL1AD, Map.of(
                "number_lap", 8, "earning", "420.50", "begin_path_a", "begin_path_a", "conditioner_time", "02:30"));
        assertThat(m.numberLap()).isEqualTo(8);
        assertThat(m.earning()).isEqualByComparingTo(new BigDecimal("420.50"));
        assertThat(m.beginPathA()).isEqualTo("begin_path_a");
        assertThat(m.beginPathB()).isNull();
        assertThat(m.trips()).isNull();
        assertThat(m.conditionerHours()).isEqualTo(2.5);
    }

    @Test
    @DisplayName("5Б-БМ: грузооборот = масса × расстояние, как раньше")
    void cargoIntl() {
        var m = RefChannelService.returnMetrics(RefChannelRules.Form.WAYBILL5BBM, Map.of(
                "cargo_capacity", "20", "cargo_distance", "1500"));
        assertThat(m.transportWork()).isEqualTo(30000d);
        assertThat(m.numberLap()).isNull();
    }
}
