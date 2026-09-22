package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.client.MasterDataClient;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.web.error.ApiErrors.ConflictException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MIGRATION.md 9.8 — legacy waybill/confirm: врач/механик по РМА, 409 при повторе, 404 чужой организации. */
class RefChannelServiceTest {

    private final WaybillRepository waybills = mock(WaybillRepository.class);
    private final WaybillService waybillService = mock(WaybillService.class);
    private final MasterDataClient masterData = mock(MasterDataClient.class);
    private final RefChannelService service = new RefChannelService(waybills, waybillService, mock(WorkDayService.class),
            masterData, mock(FuelPrefillService.class));

    private final UUID id = UUID.randomUUID();

    private Map<String, Object> body(String employeeRma) {
        Map<String, Object> m = new HashMap<>();
        m.put("organization_rma", "025680800");
        m.put("waybill_type", 1);
        m.put("waybill_id", id.toString());
        m.put("employee_rma", employeeRma);
        return m;
    }

    private Waybill bus(boolean med, boolean tech) {
        Waybill wb = mock(Waybill.class);
        when(wb.getId()).thenReturn(id);
        when(wb.getWaybillType()).thenReturn(WaybillType.WB_BUS);
        when(wb.getOrganizationRma()).thenReturn("025680800");
        when(wb.isMedPassed()).thenReturn(med);
        when(wb.isTechPassed()).thenReturn(tech);
        return wb;
    }

    @Test
    void doctorConfirmsOnceThenConflict() {
        when(masterData.findOrganization("025680800")).thenReturn(Optional.of(Map.of("id", "o1", "rma", "025680800")));
        when(masterData.findEmployee("111111111")).thenReturn(Optional.of(Map.of("rma", "111111111", "type", 1)));
        // ВАЖНО (Mockito): моки ПЛ собираются ДО when(...) — stubbing внутри аргумента thenReturn
        // даёт UnfinishedStubbingException.
        Waybill fresh = bus(false, false);
        when(waybills.findById(id)).thenReturn(Optional.of(fresh));
        assertThat(service.confirm(body("111111111"))).isTrue();
        verify(waybillService).confirmMed(eq(id), eq("111111111"), eq(true), any());

        Waybill alreadyMed = bus(true, false);
        when(waybills.findById(id)).thenReturn(Optional.of(alreadyMed));
        assertThatThrownBy(() -> service.confirm(body("111111111")))
                .isInstanceOf(ConflictException.class).hasMessage("This employee has already confirmed this waybill");
    }

    @Test
    void mechanicConfirmsAndDispatcherIsRejected() {
        when(masterData.findOrganization("025680800")).thenReturn(Optional.of(Map.of("id", "o1", "rma", "025680800")));
        when(masterData.findEmployee("222222222")).thenReturn(Optional.of(Map.of("rma", "222222222", "type", 2)));
        when(masterData.findEmployee("333333333")).thenReturn(Optional.of(Map.of("rma", "333333333", "type", 3)));
        Waybill wb = bus(true, false);
        when(waybills.findById(id)).thenReturn(Optional.of(wb));
        assertThat(service.confirm(body("222222222"))).isTrue();
        verify(waybillService).confirmTech(eq(id), eq("222222222"), anyBoolean(), any(), eq(null));
        assertThatThrownBy(() -> service.confirm(body("333333333")))
                .isInstanceOf(ConflictException.class).hasMessage("This employee is not doctor or mechanic");
    }

    @Test
    void wrongOrganizationOrTypeIs404() {
        when(masterData.findOrganization("025680800")).thenReturn(Optional.of(Map.of("id", "o1", "rma", "025680800")));
        when(masterData.findEmployee("111111111")).thenReturn(Optional.of(Map.of("rma", "111111111", "type", 1)));
        Waybill other = bus(false, false);
        when(other.getOrganizationRma()).thenReturn("999999999");
        when(waybills.findById(id)).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.confirm(body("111111111")))
                .isInstanceOf(NotFoundException.class).hasMessage("Waybill not found or does not belong to this organization");
        // waybill_type 5 (2-Б) для автобусного ПЛ — «не та таблица» legacy → 404
        Map<String, Object> b = body("111111111");
        b.put("waybill_type", 5);
        Waybill busWb = bus(false, false);
        when(waybills.findById(id)).thenReturn(Optional.of(busWb));
        assertThatThrownBy(() -> service.confirm(b)).isInstanceOf(NotFoundException.class);
        verify(waybillService, never()).confirmMed(any(), any(), anyBoolean(), any());
    }
}
