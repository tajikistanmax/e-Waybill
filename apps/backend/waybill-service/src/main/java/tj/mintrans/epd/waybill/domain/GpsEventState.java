package tj.mintrans.epd.waybill.domain;

import java.util.Locale;

/**
 * Состояние GPS-события Smart-city (legacy {@code GpsData::ENTER_INTO_ROUTE..EXIT_FROM_COMPANY} = 1..4,
 * в API — snake_case {@code enter_into_route} и т.д.). MIGRATION.md 9.7 / 12.12.
 */
public enum GpsEventState {
    ENTER_INTO_ROUTE(1, "Въезд на маршрут"),
    EXIT_FROM_ROUTE(2, "Выезд с маршрута"),
    ENTER_INTO_COMPANY(3, "Въезд в компанию"),
    EXIT_FROM_COMPANY(4, "Выезд из компании");

    private final int legacyCode;
    private final String label;

    GpsEventState(int legacyCode, String label) {
        this.legacyCode = legacyCode;
        this.label = label;
    }

    public int legacyCode() { return legacyCode; }

    public String label() { return label; }

    /** Маршрутное состояние — требует направления А/Б. */
    public boolean isRoute() {
        return this == ENTER_INTO_ROUTE || this == EXIT_FROM_ROUTE;
    }

    /** Разбор из API: {@code enter_into_route} / {@code ENTER_INTO_ROUTE} / legacy-код «1». */
    public static GpsEventState parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (GpsEventState v : values()) {
            if (v.name().equals(s) || String.valueOf(v.legacyCode).equals(s)) {
                return v;
            }
        }
        return null;
    }
}
