package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.domain.WaybillType;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Разрез сводного отчёта (MIGRATION.md 6.3): PASSENGER / CARGO / ALL и отбор конкретной формы ПЛ —
 * как в старой платформе, где отчёт строился по выбранной форме (Шакли 3-С / 1-А / 2-Б / 5Б-БМ).
 */
class RegionalBillMatchTest {

    private static boolean matches(String kind, WaybillType type) {
        try {
            Method m = RegionalReportService.class.getDeclaredMethod("billMatches", String.class, WaybillType.class);
            m.setAccessible(true);
            return (boolean) m.invoke(null, kind, type);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("PASSENGER / CARGO / ALL — прежние наборы видов")
    void groups() {
        assertThat(matches("PASSENGER", WaybillType.WB_BUS)).isTrue();
        assertThat(matches("PASSENGER", WaybillType.WB_PAX_INTL)).isTrue();
        assertThat(matches("PASSENGER", WaybillType.WB_TRUCK)).isFalse();
        assertThat(matches("CARGO", WaybillType.WB_TRUCK)).isTrue();
        assertThat(matches("CARGO", WaybillType.WB_TRUCK_INTL)).isTrue();
        assertThat(matches("CARGO", WaybillType.WB_DANGEROUS)).isTrue();
        assertThat(matches("CARGO", WaybillType.WB_MINIBUS)).isFalse();
        assertThat(matches("ALL", WaybillType.WB_TAXI)).isTrue();
        assertThat(matches("ALL", WaybillType.WB_SPECIAL)).isTrue();
    }

    @Test
    @DisplayName("имя вида ПЛ — отбор только этой формы (Шакли 2-Б, 5Б-БМ, 3-С, 1-А)")
    void exactForm() {
        assertThat(matches("WB_TRUCK", WaybillType.WB_TRUCK)).isTrue();
        assertThat(matches("WB_TRUCK", WaybillType.WB_TRUCK_INTL)).isFalse();
        assertThat(matches("WB_TRUCK_INTL", WaybillType.WB_TRUCK_INTL)).isTrue();
        assertThat(matches("WB_MINIBUS", WaybillType.WB_MINIBUS)).isTrue();
        assertThat(matches("WB_CAR", WaybillType.WB_TAXI)).isFalse();
        // Неизвестное значение не должно молча превращаться в «все»: ничего не совпадает.
        assertThat(matches("NONSENSE", WaybillType.WB_BUS)).isFalse();
    }
}
