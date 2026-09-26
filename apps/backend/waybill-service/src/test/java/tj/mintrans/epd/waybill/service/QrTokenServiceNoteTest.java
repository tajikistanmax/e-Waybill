package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tj.mintrans.epd.waybill.domain.ConsignmentNote;
import tj.mintrans.epd.waybill.domain.Waybill;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Сверка 25.09, B6 — собственный QR борхата: подпись с typ=CONSIGNMENT_NOTE, без срока, проверяется. */
class QrTokenServiceNoteTest {

    @Test
    @DisplayName("QR борхата: jti = id борхата, typ = CONSIGNMENT_NOTE, № борхата и листа; проходит verify()")
    void signAndVerify() throws Exception {
        QrTokenService qr = new QrTokenService("", "stub");
        ConsignmentNote n = new ConsignmentNote();
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(n, "id", id);
        ReflectionTestUtils.setField(n, "number", 12L);
        Waybill wb = new Waybill();
        wb.setNumber("05-26-06-0000132-0");

        Map<String, Object> claims = qr.verify(qr.sign(n, wb));
        assertThat(claims.get("jti")).isEqualTo(id.toString());
        assertThat(claims.get("typ")).isEqualTo("CONSIGNMENT_NOTE");
        assertThat(claims.get("num")).isEqualTo("12");
        assertThat(claims.get("wbn")).isEqualTo("05-26-06-0000132-0");
        assertThat(claims).doesNotContainKey("exp");
    }
}
