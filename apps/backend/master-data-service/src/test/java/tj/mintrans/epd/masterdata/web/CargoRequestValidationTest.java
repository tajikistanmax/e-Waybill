package tj.mintrans.epd.masterdata.web;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIGRATION.md §12.15 — обязательные поля груза как в legacy {@code CargoRequest}: name, type, unit — required
 * (number — автономер, 2.25; class и с 24.09.2026 price — необязательны).
 */
class CargoRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("все обязательные поля заданы — без нарушений; класс груза необязателен")
    void validCargo() {
        assertThat(validator.validate(new DictionaryController.CargoRequest(null, "Цемент навалом", "сыпучий", "т", new BigDecimal("10.5"), null))).isEmpty();
    }

    @Test
    @DisplayName("пустые тип/единица — два нарушения; цена с 24.09.2026 необязательна (анализ базы 9.3 п. 2)")
    void missingRequired() {
        Set<ConstraintViolation<DictionaryController.CargoRequest>> v =
                validator.validate(new DictionaryController.CargoRequest(null, "Песок", " ", "", null, (short) 1));

        assertThat(v).extracting(c -> c.getPropertyPath().toString()).containsExactlyInAnyOrder("type", "unit");
        assertThat(v).extracting(ConstraintViolation::getMessage)
                .containsExactlyInAnyOrder("Укажите тип груза", "Укажите единицу измерения груза");
    }
}
