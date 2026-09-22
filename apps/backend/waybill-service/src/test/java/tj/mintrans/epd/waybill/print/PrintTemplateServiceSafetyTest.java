package tj.mintrans.epd.waybill.print;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIGRATION.md §7.1 — редактируемые печатные шаблоны: белый список имён, лимит размера и запрет опасных
 * выражений (доступ к бинам/типам/конструкторам, препроцессинг) в тексте, который правит администратор.
 */
class PrintTemplateServiceSafetyTest {

    @Test
    @DisplayName("имена — только встроенные шаблоны; встроенный текст читается из classpath")
    void builtInNames() {
        assertThatCode(() -> PrintTemplateService.requireBuiltIn("waybill1ad")).doesNotThrowAnyException();
        assertThatThrownBy(() -> PrintTemplateService.requireBuiltIn("../../etc/passwd")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> PrintTemplateService.requireBuiltIn(null)).isInstanceOf(NotFoundException.class);
        assertThat(PrintTemplateService.builtInContent("blocks")).contains("Ронанда (имзо)");
        assertThat(PrintTemplateService.BUILT_IN).containsKeys("waybill3c", "cmr", "styles", "malumotnoma");
    }

    @Test
    @DisplayName("обычные выражения бланка допустимы; бины, T(), new, getClass, препроцессинг — отклоняются")
    void forbiddenExpressions() {
        String ok = "<div th:text=\"${driverName}\"></div><th:block th:replace=\"~{print/styles :: styles}\"/>"
                + "<span th:if=\"${doctorMark.present}\" th:text=\"${#lists.isEmpty(fuels)} + ' ' + ${fuelCoefficient}\"></span>";
        assertThat(PrintTemplateService.firstForbiddenExpression(ok)).isNull();
        assertThatCode(() -> PrintTemplateService.assertContentAcceptable(ok)).doesNotThrowAnyException();

        assertThat(PrintTemplateService.firstForbiddenExpression("<p th:text=\"${@waybillService.close(x)}\"/>")).contains("@waybillService");
        assertThat(PrintTemplateService.firstForbiddenExpression("<p th:text=\"${T(java.lang.Runtime).getRuntime()}\"/>")).contains("T(");
        assertThat(PrintTemplateService.firstForbiddenExpression("<p th:text=\"${new java.io.File('x')}\"/>")).contains("new java");
        assertThat(PrintTemplateService.firstForbiddenExpression("<p th:text=\"${driverName.getClass()}\"/>")).contains("getClass");
        assertThat(PrintTemplateService.firstForbiddenExpression("<p th:text=\"__${x}__\"/>")).contains("препроцессинг");
        // Текст вне выражений с теми же словами — не выражение, допустим.
        assertThat(PrintTemplateService.firstForbiddenExpression("<p>new order T(x) @user</p>")).isNull();

        assertThatThrownBy(() -> PrintTemplateService.assertContentAcceptable("  ")).isInstanceOf(UnprocessableException.class);
        assertThatThrownBy(() -> PrintTemplateService.assertContentAcceptable("<p th:text=\"${@bean}\"/>"))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("Недопустимое выражение");
        assertThatThrownBy(() -> PrintTemplateService.assertContentAcceptable("x".repeat(PrintTemplateService.MAX_BYTES + 1)))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("512");
    }
}
