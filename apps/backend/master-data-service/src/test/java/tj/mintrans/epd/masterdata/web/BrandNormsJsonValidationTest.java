package tj.mintrans.epd.masterdata.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Сверка 25.09.2026 (E2): нормативы марки — список строк вид топлива 1–5 + расход > 0, иначе 422. */
class BrandNormsJsonValidationTest {

    @Test
    @DisplayName("корректные строки и пусто — принимаются")
    void valid() {
        assertThatCode(() -> LegacyReferenceController.assertNormsJson("[{\"fuel_id\":2,\"consumption\":25.5}]", "Норма"))
                .doesNotThrowAnyException();
        assertThatCode(() -> LegacyReferenceController.assertNormsJson("[{\"fuel_id\":\"1\",\"consumption\":\"30\"},{\"fuel_id\":3,\"consumption\":35}]", "Норма"))
                .doesNotThrowAnyException();
        assertThatCode(() -> LegacyReferenceController.assertNormsJson(null, "Норма")).doesNotThrowAnyException();
        assertThatCode(() -> LegacyReferenceController.assertNormsJson(" ", "Норма")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("битый JSON, не список, вид топлива 7, расход 0 — 422")
    void invalid() {
        for (String bad : new String[]{"[{fuel_id:2", "{\"fuel_id\":2,\"consumption\":25}",
                "[{\"fuel_id\":7,\"consumption\":25}]", "[{\"fuel_id\":2,\"consumption\":0}]", "[{\"consumption\":25}]"}) {
            assertThatThrownBy(() -> LegacyReferenceController.assertNormsJson(bad, "Норма"))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }
}
