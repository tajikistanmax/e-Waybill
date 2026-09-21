package tj.mintrans.epd.masterdata.web;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIGRATION.md §12.7 — диапазон класса водителя (дараҷа) 1–3, как legacy {@code degree 1..3}
 * (питает надбавку {@code cat_1/2/3} в зарплате, §5.5); пусто — допустимо.
 */
class DriverRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static DriverController.DriverRequest req(Short degree) {
        return new DriverController.DriverRequest("461930031", "025680800", null, "Назаров Н.", LocalDate.of(1990, 1, 1),
                (short) 10, null, "AB1234567", "B", LocalDate.of(2030, 1, 1), degree,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("класс 1, 3 и пусто — без нарушений")
    void validDegrees() {
        assertThat(validator.validate(req((short) 1))).isEmpty();
        assertThat(validator.validate(req((short) 3))).isEmpty();
        assertThat(validator.validate(req(null))).isEmpty();
    }

    @Test
    @DisplayName("класс 0 и 4 — нарушение «Класс водителя: 1–3»")
    void invalidDegrees() {
        for (short d : new short[]{0, 4}) {
            Set<ConstraintViolation<DriverController.DriverRequest>> v = validator.validate(req(d));
            assertThat(v).as("degree=" + d).hasSize(1);
            assertThat(v.iterator().next().getPropertyPath().toString()).isEqualTo("degree");
            assertThat(v.iterator().next().getMessage()).isEqualTo("Класс водителя: 1–3");
        }
    }
}
