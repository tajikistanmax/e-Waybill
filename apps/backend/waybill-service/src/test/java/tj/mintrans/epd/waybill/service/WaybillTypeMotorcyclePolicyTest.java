package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.domain.WaybillType;

import static org.assertj.core.api.Assertions.assertThat;

class WaybillTypeMotorcyclePolicyTest {

    @Test
    @DisplayName("мотоцикл: срок ПЛ и юридический предел — 15 дней")
    void motorcycleValidityIsFifteenDays() {
        assertThat(WaybillType.WB_MOTORCYCLE.maxValidityDays()).isEqualTo(15);
        assertThat(WaybillType.WB_MOTORCYCLE.legalMaxValidityDays()).isEqualTo(15);
    }

    @Test
    @DisplayName("мотоцикл: категория ВУ необязательна, если указана — должна подходить")
    void motorcycleLicenseCategoryIsOptionalButCheckedWhenPresent() {
        var required = WaybillService.requiredLicenseCategories(7);

        assertThat(required).contains("A");
        assertThat(WaybillService.licenseCategoriesMatch(7, "", required)).isTrue();
        assertThat(WaybillService.licenseCategoriesMatch(7, "A", required)).isTrue();
        assertThat(WaybillService.licenseCategoriesMatch(7, "B", required)).isFalse();
        assertThat(WaybillService.licenseCategoriesMatch(7, "без категории", required)).isFalse();
    }
}
