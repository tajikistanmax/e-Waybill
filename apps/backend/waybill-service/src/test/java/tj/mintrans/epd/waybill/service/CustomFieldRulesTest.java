package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Значение доп.поля ПЛ проверяется по типу поля из конструктора полей. */
class CustomFieldRulesTest {

    private static Map<String, Object> def(String type, String options) {
        return options == null
                ? Map.of("fieldKey", "f", "labelRu", "Поле", "dataType", type)
                : Map.of("fieldKey", "f", "labelRu", "Поле", "dataType", type, "options", options);
    }

    @Test
    void numberDateBooleanEnum() {
        assertThat(CustomFieldRules.valueError(def("NUMBER", null), "12.5")).isNull();
        assertThat(CustomFieldRules.valueError(def("NUMBER", null), "12,5")).isNull();
        assertThat(CustomFieldRules.valueError(def("NUMBER", null), "abc")).contains("число");

        assertThat(CustomFieldRules.valueError(def("DATE", null), "2026-09-25")).isNull();
        assertThat(CustomFieldRules.valueError(def("DATE", null), "25.09.2026")).contains("ГГГГ-ММ-ДД");

        assertThat(CustomFieldRules.valueError(def("BOOLEAN", null), "true")).isNull();
        assertThat(CustomFieldRules.valueError(def("BOOLEAN", null), "да")).isNotNull();

        assertThat(CustomFieldRules.valueError(def("ENUM", "A, B, C"), "B")).isNull();
        assertThat(CustomFieldRules.valueError(def("ENUM", "A, B, C"), "D")).contains("нет в списке");
    }

    @Test
    void emptyIsNotAnErrorAndLongTextIs() {
        assertThat(CustomFieldRules.valueError(def("NUMBER", null), null)).isNull();
        assertThat(CustomFieldRules.valueError(def("DATE", null), "  ")).isNull();
        assertThat(CustomFieldRules.valueError(def("STRING", null), "x".repeat(501))).contains("не длиннее");
    }
}
