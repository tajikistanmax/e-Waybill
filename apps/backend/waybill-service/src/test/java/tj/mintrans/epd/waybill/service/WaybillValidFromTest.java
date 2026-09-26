package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Начало срока при Т1 (плановый выезд 1-АД): с начала текущих суток до +24 ч (сверка 25.09, A21). */
class WaybillValidFromTest {

    private final ZoneId zone = ZoneId.systemDefault();
    private final OffsetDateTime now = OffsetDateTime.now(zone).withHour(9).withMinute(30);

    @Test
    @DisplayName("не задано — момент подписи, без проверки")
    void nullAllowed() {
        assertThatCode(() -> WaybillService.assertValidFrom(null, now)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("выезд сегодня раньше подписи (05:30) и вечером на завтра (+23 ч) — допустимо")
    void todayAndNextDay() {
        assertThatCode(() -> WaybillService.assertValidFrom(now.withHour(5), now)).doesNotThrowAnyException();
        assertThatCode(() -> WaybillService.assertValidFrom(now.plusHours(23), now)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("вчера или через двое суток — 422")
    void outOfWindow() {
        assertThatThrownBy(() -> WaybillService.assertValidFrom(now.minusDays(1), now))
                .isInstanceOf(UnprocessableException.class);
        assertThatThrownBy(() -> WaybillService.assertValidFrom(now.plusDays(2), now))
                .isInstanceOf(UnprocessableException.class);
    }
}
