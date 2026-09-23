package tj.mintrans.epd.waybill.print;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Время на бланках и в выгрузках — местное (Душанбе, UTC+5), а не пояс контейнера (находка 23.09.2026). */
class PrintZoneTest {

    @Test
    @DisplayName("момент 18:51 UTC печатается как 23:51 по Душанбе; переход через полночь меняет дату")
    void utcToDushanbe() {
        assertThat(PrintZone.dateTime(OffsetDateTime.parse("2026-09-23T18:51:00Z"))).isEqualTo("23.09.2026 23:51");
        assertThat(PrintZone.dateTime(OffsetDateTime.parse("2026-09-23T20:10:00Z"))).isEqualTo("24.09.2026 01:10");
        assertThat(PrintZone.local(OffsetDateTime.parse("2026-09-23T20:10:00Z")).getDayOfMonth()).isEqualTo(24);
        assertThat(PrintZone.dateTime(null)).isEqualTo("—");
    }

    @Test
    @DisplayName("ISO-строка журнала → местное время; не ISO и «—» — как есть")
    void isoToLocal() {
        assertThat(PrintZone.isoToLocal("2026-09-23T18:51:22.123456Z")).isEqualTo("23.09.2026 23:51");
        assertThat(PrintZone.isoToLocal("—")).isEqualTo("—");
        assertThat(PrintZone.isoToLocal("вчера")).isEqualTo("вчера");
    }
}
