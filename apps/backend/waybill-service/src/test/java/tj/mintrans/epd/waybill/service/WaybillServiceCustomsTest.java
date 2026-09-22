package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIGRATION.md §3.15/§1.1 — таможенное подтверждение СМР (legacy {@code validatecmr}): только 5Б-БМ, повтор идемпотентен.
 */
class WaybillServiceCustomsTest {

    private final WaybillRepository waybills = mock(WaybillRepository.class);

    private WaybillService realService() {
        // Конструктор WaybillService большой; правила confirmCustoms зависят только от репозитория ПЛ,
        // поэтому проверяем через частичный мок с реальным методом и подменённым полем waybills.
        WaybillService s = mock(WaybillService.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        try {
            var f = WaybillService.class.getDeclaredField("waybills");
            f.setAccessible(true);
            f.set(s, waybills);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return s;
    }

    private static Waybill cmr(WaybillType type, String confirmedAt) {
        Waybill w = new Waybill();
        w.setWaybillType(type);
        var td = new HashMap<String, Object>();
        if (confirmedAt != null) td.put("customsConfirmedAt", confirmedAt);
        w.setTypeData(td);
        return w;
    }

    @Test
    @DisplayName("5Б-БМ: первое подтверждение пишет таможенника и момент; повтор не меняет ничего и не падает")
    void confirmOnceIdempotent() {
        WaybillService s = realService();
        UUID id = UUID.randomUUID();
        Waybill w = cmr(WaybillType.WB_TRUCK_INTL, null);
        when(waybills.findByIdForUpdate(id)).thenReturn(Optional.of(w));
        when(waybills.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Waybill r1 = s.confirmCustoms(id, "Officer Name", "customs");
        assertThat(r1.getTypeData()).containsEntry("customsOfficerName", "Officer Name").containsEntry("customsOfficerUser", "customs");
        assertThat(String.valueOf(r1.getTypeData().get("customsConfirmedAt"))).isNotBlank();
        String first = String.valueOf(r1.getTypeData().get("customsConfirmedAt"));

        Waybill r2 = s.confirmCustoms(id, "Other", "other");
        assertThat(String.valueOf(r2.getTypeData().get("customsConfirmedAt"))).isEqualTo(first);
        assertThat(r2.getTypeData()).containsEntry("customsOfficerUser", "customs");
    }

    @Test
    @DisplayName("не 5Б-БМ → 422, ничего не сохраняется")
    void onlyIntlTruck() {
        WaybillService s = realService();
        UUID id = UUID.randomUUID();
        when(waybills.findByIdForUpdate(id)).thenReturn(Optional.of(cmr(WaybillType.WB_TRUCK, null)));
        assertThatThrownBy(() -> s.confirmCustoms(id, "x", "y")).isInstanceOf(UnprocessableException.class);
        verify(waybills, never()).save(any());
    }
}
