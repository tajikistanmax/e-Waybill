package tj.mintrans.epd.masterdata.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tj.mintrans.epd.masterdata.domain.PlatformSetting;
import tj.mintrans.epd.masterdata.repository.PlatformSettingRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Поля форм из настроек (Настройки → Поля форм, решение владельца 24.09.2026). */
class FormFieldPolicyTest {

    private FormFieldPolicy policyWith(String value) {
        var repo = mock(PlatformSettingRepository.class);
        var s = new PlatformSetting();
        s.setSettingValue(value);
        when(repo.findByCategoryAndSettingKey("forms", "organization")).thenReturn(Optional.of(s));
        return new FormFieldPolicy(repo);
    }

    private static Map<String, Object> org(String rma, String name) {
        Map<String, Object> v = new HashMap<>();
        v.put("rma", rma);
        v.put("name", name);
        return v;
    }

    @Test
    void emptySettingKeepsTodaysBehaviourOnlySystemFieldsRequired() {
        var p = policyWith("");
        var modes = p.modes("organization");
        assertThat(modes.get("rma")).isEqualTo(FormFieldPolicy.Mode.REQUIRED);
        assertThat(modes.get("name")).isEqualTo(FormFieldPolicy.Mode.REQUIRED);
        assertThat(modes.get("phone")).isEqualTo(FormFieldPolicy.Mode.SHOW);
        assertThatCode(() -> p.requireFilled("organization", org("025680800", "Тест"))).doesNotThrowAnyException();
    }

    @Test
    void requiredFieldLeftBlankIsRejectedWithItsLabel() {
        var p = policyWith("{\"phone\":\"required\",\"kpp\":\"hidden\"}");
        var values = org("025680800", "Тест");
        values.put("phone", "  ");
        assertThatThrownBy(() -> p.requireFilled("organization", values))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Телефон");
        values.put("phone", "+992372210000");
        assertThatCode(() -> p.requireFilled("organization", values)).doesNotThrowAnyException();
        assertThat(p.modes("organization").get("kpp")).isEqualTo(FormFieldPolicy.Mode.HIDDEN);
    }

    @Test
    void systemFieldsCannotBeHiddenOrWeakened() {
        var p = policyWith("");
        assertThatThrownBy(() -> p.validateSetting("organization", "{\"name\":\"hidden\"}"))
                .hasMessageContaining("Название");
        assertThatThrownBy(() -> p.validateSetting("organization", "{\"rma\":\"show\"}"))
                .hasMessageContaining("РМА");
        // Даже если в базу попало (прямой правкой), закреплённое поле остаётся обязательным.
        var broken = policyWith("{\"name\":\"hidden\"}");
        assertThat(broken.modes("organization").get("name")).isEqualTo(FormFieldPolicy.Mode.REQUIRED);
    }

    @Test
    void settingValidationRejectsUnknownFieldsBadModesAndBadJson() {
        var p = policyWith("");
        assertThatThrownBy(() -> p.validateSetting("organization", "{\"nope\":\"hidden\"}"))
                .hasMessageContaining("nope");
        assertThatThrownBy(() -> p.validateSetting("organization", "{\"phone\":\"maybe\"}"))
                .hasMessageContaining("Телефон");
        assertThatThrownBy(() -> p.validateSetting("organization", "not json"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> p.validateSetting("organization", "{\"giveFuel\":\"required\"}"))
                .hasMessageContaining("да/нет");
        assertThatCode(() -> p.validateSetting("organization", "{\"phone\":\"required\",\"kpp\":\"hidden\",\"email\":\"show\"}"))
                .doesNotThrowAnyException();
        assertThatCode(() -> p.validateSetting("organization", "")).doesNotThrowAnyException();
    }

    @Test
    void corruptedStoredValueDoesNotBreakSaving() {
        var p = policyWith("{broken");
        assertThatCode(() -> p.requireFilled("organization", org("025680800", "Тест"))).doesNotThrowAnyException();
    }
}
