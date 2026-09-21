package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.web.error.ApiErrors.ConflictException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIGRATION.md §4.7 — отметка кассы «выручка сдана»: только форма 3-С (легковой/такси) и только после
 * возврата (RETURNED / COMPLETED), как действие {@code pay} кассира в legacy.
 */
class WaybillServiceKassaRulesTest {

    @Test
    @DisplayName("3-С после возврата и закрытый — допускается")
    void allowedForCarAfterReturn() {
        assertThatCode(() -> WaybillService.assertKassaAllowed(WaybillType.WB_CAR, WaybillStatus.RETURNED)).doesNotThrowAnyException();
        assertThatCode(() -> WaybillService.assertKassaAllowed(WaybillType.WB_TAXI, WaybillStatus.COMPLETED)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("не 3-С — 422; 3-С до возврата (ACTIVE/ISSUED) — 409")
    void rejectedOtherwise() {
        assertThatThrownBy(() -> WaybillService.assertKassaAllowed(WaybillType.WB_TRUCK, WaybillStatus.RETURNED))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("3-С");
        assertThatThrownBy(() -> WaybillService.assertKassaAllowed(WaybillType.WB_BUS, WaybillStatus.COMPLETED))
                .isInstanceOf(UnprocessableException.class);
        assertThatThrownBy(() -> WaybillService.assertKassaAllowed(WaybillType.WB_TAXI, WaybillStatus.ACTIVE))
                .isInstanceOf(ConflictException.class).hasMessageContaining("ACTIVE");
        assertThatThrownBy(() -> WaybillService.assertKassaAllowed(WaybillType.WB_CAR, WaybillStatus.ISSUED))
                .isInstanceOf(ConflictException.class);
    }
}
