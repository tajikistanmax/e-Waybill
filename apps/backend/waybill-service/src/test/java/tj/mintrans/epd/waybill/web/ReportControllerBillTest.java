package tj.mintrans.epd.waybill.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.domain.WaybillType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Сверка 25.09.2026 (D1): бланки legacy {@code report_bill} → виды ПЛ типового отчёта. */
class ReportControllerBillTest {

    @Test
    @DisplayName("bus/ebus/mbus/taxi/cargo2b/cargo5bbm → виды ПЛ; all/пусто — все; неизвестный — 400")
    void billMapping() {
        assertThat(ReportController.billForms("bus")).containsExactly(WaybillType.WB_BUS);
        assertThat(ReportController.billForms("ebus")).containsExactly(WaybillType.WB_TROLLEYBUS);
        assertThat(ReportController.billForms("taxi")).containsExactlyInAnyOrder(WaybillType.WB_CAR, WaybillType.WB_TAXI);
        assertThat(ReportController.billForms("cargo5bbm")).containsExactly(WaybillType.WB_TRUCK_INTL);
        assertThat(ReportController.billForms("all")).isNull();
        assertThat(ReportController.billForms(null)).isNull();
        assertThatThrownBy(() -> ReportController.billForms("xyz"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
