package tj.mintrans.epd.waybill.print;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Часовой пояс печатных бланков и выгрузок — местное время перевозчика (Таджикистан, UTC+5).
 *
 * <p>Служба работает в контейнере с поясом UTC, моменты (выдача, подписи титулов) хранятся
 * правильно, но до 23.09.2026 форматировались как есть: на бланке «Действителен с 23.09.2026
 * 18:51» при выписке в 23:51 по Душанбе, «Сформировано» — тоже на 5 часов раньше, в XLSX журналов
 * — сырая строка «2026-09-23T18:51:22.123Z» (находка живой проверки печати). Меняется переменной
 * {@code EPD_PRINT_ZONE}; общий пояс JVM не трогаем — от него зависят границы дней в отчётах.</p>
 */
public final class PrintZone {

    public static final ZoneId ZONE = zone();

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private PrintZone() {
    }

    private static ZoneId zone() {
        String z = System.getenv("EPD_PRINT_ZONE");
        try {
            return ZoneId.of(z == null || z.isBlank() ? "Asia/Dushanbe" : z.trim());
        } catch (RuntimeException e) {
            return ZoneId.of("Asia/Dushanbe");
        }
    }

    /** Момент в местном времени (null → null). */
    public static ZonedDateTime local(OffsetDateTime t) {
        return t == null ? null : t.atZoneSameInstant(ZONE);
    }

    /** «dd.MM.yyyy HH:mm» в местном времени; null → «—». */
    public static String dateTime(OffsetDateTime t) {
        return t == null ? "—" : DT.format(t.atZoneSameInstant(ZONE));
    }

    /** Сейчас, «dd.MM.yyyy HH:mm», местное время. */
    public static String now() {
        return DT.format(ZonedDateTime.now(ZONE));
    }

    /** ISO-строка момента (как её отдаёт JSON журналов) → «dd.MM.yyyy HH:mm» местного; не ISO — как есть. */
    public static String isoToLocal(String iso) {
        if (iso == null || iso.isBlank() || "—".equals(iso)) {
            return iso;
        }
        try {
            return dateTime(OffsetDateTime.parse(iso));
        } catch (DateTimeParseException e) {
            return iso;
        }
    }
}
