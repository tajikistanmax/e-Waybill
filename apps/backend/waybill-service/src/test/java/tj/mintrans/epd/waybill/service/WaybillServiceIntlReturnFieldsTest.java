package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.service.WaybillService.ReturnMetrics;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIGRATION.md §3.14/§3.15 — поля возврата международных форм: время прибытия в пункт назначения
 * (legacy 5Б-БМ {@code arrival_time}) и перевезённые пассажиры (legacy 4-МБМ {@code number_passengers}).
 */
class WaybillServiceIntlReturnFieldsTest {

    private static ReturnMetrics of(String arrival, Integer passengers) {
        return new ReturnMetrics(null, null, null, null, arrival, passengers);
    }

    @Test
    @DisplayName("4-МБМ: время прибытия нормализуется в ISO, пассажиры ≥ 0 принимаются; пустая строка = не задано")
    void paxIntlAccepts() {
        assertThat(WaybillService.assertIntlReturnFields(WaybillType.WB_PAX_INTL, of("2026-09-22T14:30", 41)))
                .isEqualTo("2026-09-22T14:30");
        assertThat(WaybillService.assertIntlReturnFields(WaybillType.WB_PAX_INTL, of("  ", 0))).isNull();
        assertThat(WaybillService.assertIntlReturnFields(WaybillType.WB_PAX_INTL, null)).isNull();
        assertThat(WaybillService.assertIntlReturnFields(WaybillType.WB_TRUCK_INTL, of("2026-09-22T14:30:15", null)))
                .isEqualTo("2026-09-22T14:30:15");
    }

    @Test
    @DisplayName("прибытие только у 5Б-БМ/4-МБМ, пассажиры только у 4-МБМ, формат ISO, не отрицательно — иначе 422")
    void rejects() {
        assertThatThrownBy(() -> WaybillService.assertIntlReturnFields(WaybillType.WB_TRUCK, of("2026-09-22T14:30", null)))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("arrivalTime");
        assertThatThrownBy(() -> WaybillService.assertIntlReturnFields(WaybillType.WB_PAX_INTL, of("22.09.2026 14:30", null)))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("формате");
        assertThatThrownBy(() -> WaybillService.assertIntlReturnFields(WaybillType.WB_TRUCK_INTL, of(null, 5)))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("passengersCount");
        assertThatThrownBy(() -> WaybillService.assertIntlReturnFields(WaybillType.WB_PAX_INTL, of(null, -1)))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("отрицательным");
    }
}
