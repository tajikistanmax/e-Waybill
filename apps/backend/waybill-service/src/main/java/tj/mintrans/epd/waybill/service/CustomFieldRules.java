package tj.mintrans.epd.waybill.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Map;

/**
 * Проверка значения дополнительного поля ПЛ (конструктор полей, master-data) по его типу.
 * Раньше сервер проверял только обязательность: API-клиент мог прислать «abc» в числовое поле,
 * «вчера» в дату или вариант, которого нет в списке, — и это попадало в документ и на бланк.
 */
final class CustomFieldRules {

    static final int MAX_TEXT = 500;

    private CustomFieldRules() {
    }

    /** Ошибка для пользователя или {@code null}, если значение подходит. Пустое значение — не ошибка. */
    static String valueError(Map<String, Object> definition, Object value) {
        String v = value == null ? "" : String.valueOf(value).trim();
        if (v.isEmpty()) {
            return null;
        }
        String label = String.valueOf(definition.getOrDefault("labelRu", definition.get("fieldKey")));
        String type = String.valueOf(definition.getOrDefault("dataType", "STRING"));
        switch (type) {
            case "NUMBER" -> {
                try {
                    Double.parseDouble(v.replace(',', '.'));
                } catch (NumberFormatException e) {
                    return "Поле «%s» — число (например 12.5)".formatted(label);
                }
            }
            case "DATE" -> {
                try {
                    LocalDate.parse(v);
                } catch (DateTimeParseException e) {
                    return "Поле «%s» — дата в формате ГГГГ-ММ-ДД".formatted(label);
                }
            }
            case "BOOLEAN" -> {
                if (!"true".equals(v) && !"false".equals(v)) {
                    return "Поле «%s» — да или нет (true/false)".formatted(label);
                }
            }
            case "ENUM" -> {
                String options = definition.get("options") == null ? "" : String.valueOf(definition.get("options"));
                boolean known = Arrays.stream(options.split(",")).map(String::trim).anyMatch(v::equals);
                if (!known) {
                    return "Поле «%s»: «%s» нет в списке вариантов (%s)".formatted(label, v, options);
                }
            }
            default -> {
                if (v.length() > MAX_TEXT) {
                    return "Поле «%s» — не длиннее %d символов".formatted(label, MAX_TEXT);
                }
            }
        }
        return null;
    }
}
