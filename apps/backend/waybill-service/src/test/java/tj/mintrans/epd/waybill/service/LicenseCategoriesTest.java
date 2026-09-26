package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Категории ВУ из текста реестра — реальные записи перенесённой базы (сверка 25.09, A22). */
class LicenseCategoriesTest {

    @ParameterizedTest(name = "«{0}» → {1}")
    @DisplayName("разбор записей реестра")
    @CsvSource(delimiter = '|', value = {
            "BCD|B,C,D",
            "ВСД|B,C,D",            // кириллица
            "БсД|B,C,D",
            "В.С.Д|B,C,D",
            "'В,С,Д'|B,C,D",
            "ВВ1СС1|B,B1,C,C1",
            "В В1 С С1|B,B1,C,C1",
            "BCDЕ|B,C,D,E",        // латиница + кириллическая Е
            "вс|B,C",
            "АВСДЕ|A,B,C,D,E",
            "D|D",
    })
    void parse(String raw, String expected) {
        assertThat(LicenseCategories.parse(raw)).contains(Set.of(expected.split(",")));
    }

    @ParameterizedTest
    @DisplayName("неразборчивое или пустое — не проверяется")
    @ValueSource(strings = {"3", "68008", "", "   ", "нет"})
    void unparseable(String raw) {
        assertThat(LicenseCategories.parse(raw)).isEmpty();
        assertThat(LicenseCategories.hasAny(raw, Set.of("D"))).isTrue();
    }

    @ParameterizedTest(name = "«{0}», вид ТС {1} → {2}")
    @DisplayName("соответствие виду ТС")
    @CsvSource({
            "ВСД,1,true",      // автобус — D
            "ВС,1,false",
            "ВД1,3,true",      // микроавтобус — D1 достаточно
            "ВД1,1,false",     // автобусу D1 мало
            "ВВ1СС1,5,true",   // грузовой — C
            "B,4,true",
            "C,4,false",
    })
    void matchesVehicle(String raw, int transportType, boolean ok) {
        assertThat(LicenseCategories.hasAny(raw, WaybillService.requiredLicenseCategories(transportType))).isEqualTo(ok);
    }
}
