package tj.mintrans.epd.masterdata.web;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIGRATION.md §2.28 — поля формы маршрута legacy (пункты А/Б, время рейса, срок свидетельства,
 * город, координаты): необязательны, координаты в допустимых диапазонах.
 */
class RouteRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static DictionaryController.RouteRequest req(BigDecimal lat, BigDecimal lon) {
        // Первый компонент — id записи (null = апсерт по номеру в организации, MIGRATION.md 8.11).
        return new DictionaryController.RouteRequest(null, "3", "Вокзал — Аэропорт", (short) 1, (short) 1, null, null,
                null, null, null, null, null, null, null, null, null, null,
                12d, 12d, 0d, 0d, (short) 8, 0.7d, 4d,
                "Вокзал", "Аэропорт", LocalTime.of(0, 45), LocalTime.of(0, 50), LocalDate.of(2027, 1, 1),
                "Душанбе", lat, lon);
    }

    @Test
    @DisplayName("все поля формы заполнены корректно — без нарушений; без координат — тоже")
    void validPasses() {
        assertThat(validator.validate(req(new BigDecimal("38.5598"), new BigDecimal("68.7870")))).isEmpty();
        assertThat(validator.validate(req(null, null))).isEmpty();
    }

    @Test
    @DisplayName("широта 91 и долгота −181 — нарушения диапазонов")
    void coordinatesOutOfRange() {
        Set<ConstraintViolation<DictionaryController.RouteRequest>> v =
                validator.validate(req(new BigDecimal("91"), new BigDecimal("-181")));

        assertThat(v).extracting(c -> c.getPropertyPath().toString()).containsExactlyInAnyOrder("latitude", "longitude");
        assertThat(v).extracting(ConstraintViolation::getMessage).contains("Широта: −90…90", "Долгота: −180…180");
    }
}
