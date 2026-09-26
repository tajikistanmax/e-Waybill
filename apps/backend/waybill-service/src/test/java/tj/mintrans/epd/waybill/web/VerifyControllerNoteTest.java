package tj.mintrans.epd.waybill.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tj.mintrans.epd.waybill.domain.ConsignmentNote;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.repository.ConsignmentNoteRepository;
import tj.mintrans.epd.waybill.repository.MalumotnomaRepository;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.service.QrTokenService;
import tj.mintrans.epd.waybill.service.VerifyScanLogService;
import tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Сверка 25.09, B6 — проверка борхата по его собственному QR на публичном портале. */
class VerifyControllerNoteTest {

    @Test
    @DisplayName("QR борхата → kind CONSIGNMENT_NOTE: №, лист, статус листа, стороны, груз; неизвестный борхат — 404")
    void verifyNote() throws Exception {
        QrTokenService qr = new QrTokenService("", "stub");
        WaybillRepository waybills = mock(WaybillRepository.class);
        ConsignmentNoteRepository notes = mock(ConsignmentNoteRepository.class);
        VerifyController c = new VerifyController(qr, waybills, mock(MalumotnomaRepository.class), mock(VerifyScanLogService.class));
        c.setNotes(notes);

        Waybill wb = new Waybill();
        UUID wbId = UUID.randomUUID();
        ReflectionTestUtils.setField(wb, "id", wbId);
        wb.setNumber("05-26-06-0000132-0");
        wb.setStatus(WaybillStatus.ACTIVE);
        wb.setVehicleRegNumber("3101DM01");
        ConsignmentNote n = new ConsignmentNote();
        UUID noteId = UUID.randomUUID();
        ReflectionTestUtils.setField(n, "id", noteId);
        ReflectionTestUtils.setField(n, "number", 7L);
        n.setWaybillId(wbId);
        n.setKind((short) 1);
        n.setTrips(3);
        n.setCargoName("Щебень");
        n.setCargoWeight(new BigDecimal("12.5"));
        n.setSenderName("Карьер");
        n.setReceiverName("Стройка");
        when(notes.findById(noteId)).thenReturn(Optional.of(n));
        when(waybills.findById(wbId)).thenReturn(Optional.of(wb));

        Map<String, Object> r = c.verify(qr.sign(n, wb), mock(HttpServletRequest.class));
        assertThat(r.get("kind")).isEqualTo("CONSIGNMENT_NOTE");
        assertThat(r.get("number")).isEqualTo(7L);
        assertThat(r.get("waybillNumber")).isEqualTo("05-26-06-0000132-0");
        assertThat(r.get("onlineStatus")).isEqualTo("ACTIVE");
        assertThat(r.get("cargoName")).isEqualTo("Щебень");
        assertThat(r.get("trips")).isEqualTo(3);

        when(notes.findById(any())).thenReturn(Optional.empty());
        String token = qr.sign(n, wb);
        assertThatThrownBy(() -> c.verify(token, mock(HttpServletRequest.class))).isInstanceOf(NotFoundException.class);
    }
}
