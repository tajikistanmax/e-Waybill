package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.domain.GpsEventState;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** MIGRATION.md 9.7 / 12.12 — правила GPS-событий 1-в-1 с legacy StoreGpsDataRequest. */
class GpsEventRulesTest {

    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 9, 22, 10, 0, 0, 0, ZoneOffset.ofHours(5));

    @Test
    void directionRequiredForRouteStatesAndDistanceForCompanyEntry() {
        assertThat(GpsEventRules.requestError(GpsEventState.ENTER_INTO_ROUTE, null, null)).isPresent();
        assertThat(GpsEventRules.requestError(GpsEventState.EXIT_FROM_ROUTE, "C", null)).isPresent();
        assertThat(GpsEventRules.requestError(GpsEventState.ENTER_INTO_ROUTE, "б", null)).isEmpty();
        assertThat(GpsEventRules.requestError(GpsEventState.ENTER_INTO_COMPANY, null, null)).isPresent();
        assertThat(GpsEventRules.requestError(GpsEventState.ENTER_INTO_COMPANY, null, new BigDecimal("-1"))).isPresent();
        assertThat(GpsEventRules.requestError(GpsEventState.ENTER_INTO_COMPANY, null, new BigDecimal("12.5"))).isEmpty();
        assertThat(GpsEventRules.requestError(GpsEventState.EXIT_FROM_COMPANY, null, null)).isEmpty();
        assertThat(GpsEventRules.requestError(null, null, null)).isPresent();
        assertThat(GpsEventRules.normalizeDirection("А")).isEqualTo("A");
        assertThat(GpsEventRules.normalizeDirection(" b ")).isEqualTo("B");
    }

    @Test
    void repeatOfSameStateWithinCooldownIsRejected() {
        assertThat(GpsEventRules.cooldownError(GpsEventState.ENTER_INTO_COMPANY, T0, T0.minusMinutes(19), null, 20))
                .contains("Повторная регистрация заезда в предприятие возможна только через 20 минут.");
        assertThat(GpsEventRules.cooldownError(GpsEventState.ENTER_INTO_COMPANY, T0, T0.minusMinutes(20), null, 20)).isEmpty();
        assertThat(GpsEventRules.cooldownError(GpsEventState.ENTER_INTO_ROUTE, T0, T0.minusMinutes(5), null, 20))
                .contains("Повторная регистрация выезда на маршрут для данного направления возможна только через 20 минут.");
        assertThat(GpsEventRules.cooldownError(GpsEventState.EXIT_FROM_COMPANY, T0, null, null, 20)).isEmpty();
    }

    @Test
    void exitFromRouteMustFollowEnterByCooldown() {
        assertThat(GpsEventRules.cooldownError(GpsEventState.EXIT_FROM_ROUTE, T0, null, T0.minusMinutes(10), 20))
                .contains("Заезд с маршрута возможен только через 20 минут после выезда на маршрут.");
        assertThat(GpsEventRules.cooldownError(GpsEventState.EXIT_FROM_ROUTE, T0, null, T0.minusMinutes(25), 20)).isEmpty();
        // Полные метки времени: въезд вчера 23:50, выезд сегодня 00:05 — 15 минут (в legacy разница по H:i:s была отрицательной).
        OffsetDateTime enter = OffsetDateTime.of(2026, 9, 21, 23, 50, 0, 0, ZoneOffset.ofHours(5));
        OffsetDateTime exit = OffsetDateTime.of(2026, 9, 22, 0, 5, 0, 0, ZoneOffset.ofHours(5));
        assertThat(GpsEventRules.cooldownError(GpsEventState.EXIT_FROM_ROUTE, exit, null, enter, 20)).isPresent();
        assertThat(GpsEventRules.cooldownError(GpsEventState.EXIT_FROM_ROUTE, exit.plusMinutes(10), null, enter, 20)).isEmpty();
    }

    @Test
    void parseAcceptsSnakeCaseAndLegacyCodes() {
        assertThat(GpsEventState.parse("enter_into_route")).isEqualTo(GpsEventState.ENTER_INTO_ROUTE);
        assertThat(GpsEventState.parse("EXIT_FROM_COMPANY")).isEqualTo(GpsEventState.EXIT_FROM_COMPANY);
        assertThat(GpsEventState.parse("3")).isEqualTo(GpsEventState.ENTER_INTO_COMPANY);
        assertThat(GpsEventState.parse("nope")).isNull();
    }
}
