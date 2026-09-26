package tj.mintrans.epd.waybill.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tj.mintrans.epd.waybill.client.MasterDataClient;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.repository.MalumotnomaRepository;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.service.QrTokenService;
import tj.mintrans.epd.waybill.service.VerifyScanLogService;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Сверка 25.09, G6: публичная проверка листа показывает то, что нужно для сверки на месте (маршрут,
 * стоянка, карта контроля, ВУ, фото водителя), но не паспорт и не полный номер удостоверения.
 */
class VerifyControllerDetailsTest {

    @Test
    @DisplayName("маршрут, карта контроля, ВУ (номер — хвост), одобренное фото; выключено настройкой — без фото")
    void details() throws Exception {
        QrTokenService qr = new QrTokenService("", "stub");
        WaybillRepository waybills = mock(WaybillRepository.class);
        MasterDataClient masterData = mock(MasterDataClient.class);
        VerifyController c = new VerifyController(qr, waybills, mock(MalumotnomaRepository.class), mock(VerifyScanLogService.class));
        c.setMasterData(masterData);

        Waybill wb = new Waybill();
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(wb, "id", id);
        wb.setNumber("01-26-02-0000128-6");
        wb.setWaybillType(WaybillType.WB_BUS);
        wb.setStatus(WaybillStatus.ISSUED);
        wb.setDriverRma("9924020201");
        wb.setRoute("8 · Вокзал — Зарафшон");
        wb.setValidFrom(OffsetDateTime.now().minusHours(1));
        wb.setValidTo(OffsetDateTime.now().plusHours(8));
        wb.setVehicleSnapshot(Map.of("parkingNumber", "0012", "controlCardNumber", "KK-2101DM01",
                "controlCardValidTo", "2027-09-24"));
        wb.setDriverSnapshot(Map.of("fullName", "Носиров Бехруз", "licenseNumber", "DL020201",
                "licenseCategories", "B,D", "licenseValidTo", "2029-09-23", "passport", "A1234567"));
        when(waybills.findById(id)).thenReturn(Optional.of(wb));
        when(masterData.securitySettings()).thenReturn(Map.of());
        when(masterData.findDriverPhotoDataUri("9924020201")).thenReturn(Optional.of("data:image/png;base64,AA=="));

        Map<String, Object> r = c.verify(qr.sign(wb), mock(HttpServletRequest.class));
        assertThat(r).containsEntry("route", "8 · Вокзал — Зарафшон")
                .containsEntry("parkingNumber", "0012")
                .containsEntry("controlCardNumber", "KK-2101DM01")
                .containsEntry("licenseNumber", "•••• 0201")
                .containsEntry("licenseCategories", "B,D")
                .containsEntry("driverPhoto", "data:image/png;base64,AA==")
                .doesNotContainKey("passport");

        when(masterData.securitySettings()).thenReturn(Map.of("verify_driver_photo", "false"));
        MasterDataClient off = mock(MasterDataClient.class);
        when(off.securitySettings()).thenReturn(Map.of("verify_driver_photo", "false"));
        c.setMasterData(off);
        Map<String, Object> r2 = c.verify(qr.sign(wb), mock(HttpServletRequest.class));
        assertThat(r2).doesNotContainKey("driverPhoto");
        verify(off, never()).findDriverPhotoDataUri("9924020201");
    }

    @Test
    void maskTail() {
        assertThat(VerifyController.maskTail("DL020201")).isEqualTo("•••• 0201");
        assertThat(VerifyController.maskTail("123")).isEqualTo("••••");
        assertThat(VerifyController.maskTail(" ")).isNull();
    }
}
