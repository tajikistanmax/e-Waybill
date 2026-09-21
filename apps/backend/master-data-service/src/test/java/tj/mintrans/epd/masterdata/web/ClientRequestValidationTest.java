package tj.mintrans.epd.masterdata.web;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIGRATION.md §2.24 — вид клиента и банковские реквизиты в запросе справочника клиентов:
 * вид 1–4 (legacy {@code clients.type}), не передан — допустимо (→ 1 в контроллере), реквизиты — ограниченной длины.
 */
class ClientRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static DictionaryController.ClientRequest req(Short type, String mfo) {
        return new DictionaryController.ClientRequest("000123", "ООО Ромашка", "Душанбе", "900000000", null,
                type, "0110001234", "020000123", "20202972000000000001", "20402972316264", mfo, "Амонатбонк");
    }

    @Test
    @DisplayName("вид 3 (грузоотправитель) с полными реквизитами — без нарушений; вид не задан — тоже")
    void validTypesPass() {
        assertThat(validator.validate(req((short) 3, "350101"))).isEmpty();
        assertThat(validator.validate(req(null, "350101"))).isEmpty();
    }

    @Test
    @DisplayName("вид 5 — нарушение «Вид клиента: 1–4»; МФО длиннее 20 символов — нарушение размера")
    void invalidTypeAndTooLongMfo() {
        Set<ConstraintViolation<DictionaryController.ClientRequest>> v = validator.validate(req((short) 5, "1".repeat(21)));

        assertThat(v).extracting(c -> c.getPropertyPath().toString()).containsExactlyInAnyOrder("type", "mfo");
        assertThat(v).extracting(ConstraintViolation::getMessage).contains("Вид клиента: 1–4");
    }
}
