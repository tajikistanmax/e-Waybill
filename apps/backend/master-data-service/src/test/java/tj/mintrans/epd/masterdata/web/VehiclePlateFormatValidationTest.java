package tj.mintrans.epd.masterdata.web;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сверка 25.09.2026 (F2/F3): форматы госномера и номера стоянки — как в legacy {@code ParkingRequest}.
 * Госномер только обязателен (строгий формат — у легковых, VehicleCardRules); номер стоянки только
 * уникален в организации. Раньше «буквы и цифры» и «4 цифры» отвергали ≈10 тыс. и 42 190 перенесённых ТС.
 */
class VehiclePlateFormatValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private boolean plateOk(String v) {
        return validator.validateValue(VehicleController.VehicleRequest.class, "registrationNumber", v).isEmpty();
    }

    private boolean parkingOk(String v) {
        return validator.validateValue(VehicleController.VehicleRequest.class, "parkingNumber", v).isEmpty();
    }

    @Test
    @DisplayName("госномера legacy с дефисом и пробелом принимаются; спецсимволы и пусто — нет")
    void plates() {
        assertThat(plateOk("42-69TT05")).isTrue();
        assertThat(plateOk("0114TJ01")).isTrue();
        assertThat(plateOk("12 34 AB")).isTrue();
        assertThat(plateOk("ТРОЛ-12")).isTrue();
        assertThat(plateOk("0114TJ01;DROP")).isFalse();
        assertThat(plateOk("")).isFalse();
    }

    @Test
    @DisplayName("номер стоянки: 1–3-значные и длинные legacy-номера принимаются")
    void parkingNumbers() {
        assertThat(parkingOk("7")).isTrue();
        assertThat(parkingOk("123")).isTrue();
        assertThat(parkingOk("10457")).isTrue();
        assertThat(parkingOk("12/3")).isTrue();
        assertThat(parkingOk("12;3")).isFalse();
    }
}
