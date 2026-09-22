package tj.mintrans.epd.waybill.service;

import tj.mintrans.epd.waybill.domain.GpsEventState;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Правила GPS-событий Smart-city 1-в-1 с legacy {@code StoreGpsDataRequest} (MIGRATION.md 9.7 / 12.12):
 * <ul>
 *   <li>направление А/Б обязательно для маршрутных состояний;</li>
 *   <li>дистанция (≥ 0) обязательна для въезда в предприятие;</li>
 *   <li>повтор того же состояния (для маршрутных — того же направления) в тот же день не раньше, чем
 *       через N минут (legacy 20); выезд с маршрута — не раньше N минут после последнего въезда на него.</li>
 * </ul>
 * Отличие от legacy (не переносится как баг): интервалы считаются по полным меткам времени, а не по
 * времени суток {@code H:i:s} — в оригинале событие после полуночи давало отрицательную разницу и ложный отказ.
 */
public final class GpsEventRules {

    private static final Map<GpsEventState, String> REPEAT_MESSAGES = Map.of(
            GpsEventState.ENTER_INTO_ROUTE, "Повторная регистрация выезда на маршрут для данного направления возможна только через %d минут.",
            GpsEventState.EXIT_FROM_ROUTE, "Повторная регистрация заезда с маршрута для данного направления возможна только через %d минут.",
            GpsEventState.ENTER_INTO_COMPANY, "Повторная регистрация заезда в предприятие возможна только через %d минут.",
            GpsEventState.EXIT_FROM_COMPANY, "Повторная регистрация выезда из предприятия возможна только через %d минут.");

    private GpsEventRules() {
    }

    /** Нормализованное направление (A/B) или сообщение об ошибке запроса. */
    public static Optional<String> requestError(GpsEventState state, String direction, BigDecimal distanceKm) {
        if (state == null) {
            return Optional.of("state: допустимые значения enter_into_route, exit_from_route, enter_into_company, exit_from_company");
        }
        if (state.isRoute()) {
            String d = normalizeDirection(direction);
            if (d == null) {
                return Optional.of("direction обязательно для заполнения (A или B), если статус установлен в enter_into_route или exit_from_route.");
            }
        }
        if (state == GpsEventState.ENTER_INTO_COMPANY && distanceKm == null) {
            return Optional.of("distance обязательно для заполнения, если статус установлен в enter_into_company.");
        }
        if (distanceKm != null && distanceKm.signum() < 0) {
            return Optional.of("distance не может быть отрицательной.");
        }
        return Optional.empty();
    }

    /** «A»/«B» (принимаются также кириллические А/Б), иначе null. */
    public static String normalizeDirection(String direction) {
        if (direction == null) {
            return null;
        }
        String d = direction.trim().toUpperCase();
        return switch (d) {
            case "A", "А" -> "A";
            case "B", "Б", "В" -> "B";
            default -> null;
        };
    }

    /**
     * Проверка интервалов.
     *
     * @param lastSameState  время последнего события того же состояния (и направления) за сегодня, или null
     * @param lastEnterRoute время последнего въезда на маршрут по этому направлению (для EXIT_FROM_ROUTE), или null
     */
    public static Optional<String> cooldownError(GpsEventState state, OffsetDateTime now,
                                                 OffsetDateTime lastSameState, OffsetDateTime lastEnterRoute,
                                                 int cooldownMinutes) {
        if (lastSameState != null && minutesBetween(lastSameState, now) < cooldownMinutes) {
            return Optional.of(REPEAT_MESSAGES.get(state).formatted(cooldownMinutes));
        }
        if (state == GpsEventState.EXIT_FROM_ROUTE && lastEnterRoute != null
                && minutesBetween(lastEnterRoute, now) < cooldownMinutes) {
            return Optional.of("Заезд с маршрута возможен только через %d минут после выезда на маршрут.".formatted(cooldownMinutes));
        }
        return Optional.empty();
    }

    static long minutesBetween(OffsetDateTime from, OffsetDateTime to) {
        return Duration.between(from, to).toMinutes();
    }
}
