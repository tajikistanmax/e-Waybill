package tj.mintrans.epd.masterdata.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIGRATION.md §2.6/§12.11 — фото и подпись водителя (legacy {@code image|mimes:jpg,png,jpeg}):
 * виды PHOTO/SIGNATURE принимают только изображения; прочие виды документов — как раньше (PDF/Word тоже).
 */
class SubjectDocumentVisualTypesTest {

    @Test
    @DisplayName("PHOTO/SIGNATURE: JPEG/PNG принимаются, PDF/пусто → 422")
    void visualTypesRequireImage() {
        assertThatCode(() -> SubjectDocumentController.assertVisualTypeIsImage("PHOTO", "image/jpeg")).doesNotThrowAnyException();
        assertThatCode(() -> SubjectDocumentController.assertVisualTypeIsImage("SIGNATURE", "image/png")).doesNotThrowAnyException();
        for (String bad : new String[]{"application/pdf", null}) {
            assertThatThrownBy(() -> SubjectDocumentController.assertVisualTypeIsImage("SIGNATURE", bad))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        }
    }

    @Test
    @DisplayName("остальные виды не ограничены изображениями")
    void otherTypesUnrestricted() {
        assertThatCode(() -> SubjectDocumentController.assertVisualTypeIsImage("DRIVER_LICENSE", "application/pdf"))
                .doesNotThrowAnyException();
        assertThat(SubjectDocumentController.VISUAL_DOC_TYPES).containsExactlyInAnyOrder("PHOTO", "SIGNATURE");
    }
}
