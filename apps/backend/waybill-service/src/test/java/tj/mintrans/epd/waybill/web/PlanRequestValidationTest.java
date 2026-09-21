package tj.mintrans.epd.waybill.web;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIGRATION.md §2.29 — виды плана перевозок = legacy {@code bill_type_plan} (1 пассажирские, 2 такси,
 * 3 форма 2-Б, 4 форма 5Б-БМ): CARGO_INTL принимается, произвольная строка — нет.
 */
class PlanRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static WaybillPlanController.PlanRequest req(String kind) {
        return new WaybillPlanController.PlanRequest(null, (short) 1, 2026, null, kind, 120.5d, 3.4d, null);
    }

    @Test
    @DisplayName("все четыре вида legacy — валидны")
    void allLegacyKindsAccepted() {
        for (String kind : new String[]{"PASSENGER", "TAXI", "CARGO", "CARGO_INTL"}) {
            assertThat(validator.validate(req(kind))).as(kind).isEmpty();
        }
    }

    @Test
    @DisplayName("неизвестный вид плана — нарушение с понятным сообщением")
    void unknownKindRejected() {
        Set<ConstraintViolation<WaybillPlanController.PlanRequest>> v = validator.validate(req("FOO"));

        assertThat(v).hasSize(1);
        assertThat(v.iterator().next().getPropertyPath().toString()).isEqualTo("planKind");
        assertThat(v.iterator().next().getMessage()).isEqualTo("Вид плана: PASSENGER, TAXI, CARGO или CARGO_INTL");
    }
}
