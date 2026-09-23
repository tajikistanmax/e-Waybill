package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import tj.mintrans.epd.waybill.client.MasterDataClient;
import tj.mintrans.epd.waybill.config.CurrentUser;
import tj.mintrans.epd.waybill.config.TenantScope;
import tj.mintrans.epd.waybill.crypto.MedicalDataCrypto;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.domain.WaybillStatusEvent;
import tj.mintrans.epd.waybill.domain.WaybillTitle;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.repository.WaybillInspectionRepository;
import tj.mintrans.epd.waybill.repository.WaybillPaymentRepository;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.repository.WaybillStatusEventRepository;
import tj.mintrans.epd.waybill.repository.WaybillTitleRepository;
import tj.mintrans.epd.waybill.repository.WorkDayRepository;
import tj.mintrans.epd.waybill.signing.TitleSigner;
import tj.mintrans.epd.waybill.web.error.ApiErrors.ConflictException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Находки подготовки демонстрации 23.09.2026 по выезду и закрытию путевого листа:
 * <ul>
 *   <li>одометр выезда брался из снимка ТС на момент ВЫПИСКИ и затирал показание механика —
 *       при выросшем пробеге в карточке закрытие падало с 500;</li>
 *   <li>отказ справочника «уменьшить» пробег при закрытии давал 500, лист застревал «возвращён»;</li>
 *   <li>повторный Т6 делал закрытие невозможным (409 «Неоднозначные данные»);</li>
 *   <li>в отметках Т4/Т5 не было Ф.И.О. диспетчера — бланк печатал его РМА.</li>
 * </ul>
 */
class WaybillServiceExitCloseTest {

    private static final String ORG_ID = "11111111-1111-1111-1111-111111111111";
    private static final String DISP = "333333333";

    private final WaybillRepository waybills = mock(WaybillRepository.class);
    private final WaybillTitleRepository titles = mock(WaybillTitleRepository.class);
    private final WaybillStatusEventRepository events = mock(WaybillStatusEventRepository.class);
    private final MasterDataClient masterData = mock(MasterDataClient.class);
    private final TenantScope tenant = mock(TenantScope.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final TitleSigner signer = mock(TitleSigner.class);
    private final MedicalDataCrypto crypto = mock(MedicalDataCrypto.class);
    private WaybillService service;
    private final java.util.List<WaybillTitle> savedTitles = new java.util.ArrayList<>();
    private final java.util.List<WaybillStatusEvent> savedEvents = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new WaybillService(waybills, titles, events, mock(WaybillPaymentRepository.class), masterData,
                mock(WaybillNumberGenerator.class), mock(BranchSerialGenerator.class),
                mock(ApplicationEventPublisher.class), tenant, currentUser,
                mock(WaybillInspectionRepository.class), signer, crypto, false, BigDecimal.TEN,
                mock(WorkDayRepository.class), false);
        when(tenant.isBounded()).thenReturn(false);
        when(waybills.save(any())).thenAnswer(i -> i.getArgument(0));
        when(titles.save(any())).thenAnswer(i -> { savedTitles.add(i.getArgument(0)); return i.getArgument(0); });
        when(events.save(any())).thenAnswer(i -> { savedEvents.add(i.getArgument(0)); return i.getArgument(0); });
        when(signer.sign(any(), anyString(), anyString(), any())).thenReturn("sig");
        when(masterData.effectivePolicies(anyString(), anyString())).thenReturn(Map.of());
        when(masterData.findEmployee(DISP)).thenReturn(Optional.of(Map.of(
                "rma", DISP, "type", 3, "name", "Назарова Мунира", "organizationId", ORG_ID)));
    }

    private Waybill waybill(WaybillStatus status, Integer snapshotOdometer) {
        Waybill w = new Waybill();
        org.springframework.test.util.ReflectionTestUtils.setField(w, "id", UUID.randomUUID());
        w.setWaybillType(WaybillType.WB_BUS);
        w.setStatus(status);
        w.setOrganizationRma("025680800");
        w.setVehicleRegNumber("9911QA01");
        w.setDriverRma("555555555");
        w.setOrganizationSnapshot(Map.of("id", ORG_ID));
        var veh = new java.util.HashMap<String, Object>();
        veh.put("id", "22222222-2222-2222-2222-222222222222");
        if (snapshotOdometer != null) veh.put("odometer", snapshotOdometer);
        w.setVehicleSnapshot(veh);
        when(waybills.findByIdForUpdate(w.getId())).thenReturn(Optional.of(w));
        return w;
    }

    @Test
    @DisplayName("выезд без явного одометра: берётся больший из показания механика (Т3) и ТЕКУЩЕГО пробега в справочнике")
    void exitOdometerIsMaxOfMechanicAndCurrentRegistry() {
        Waybill w = waybill(WaybillStatus.ISSUED, 100);   // снимок при выписке — 100
        w.setOdometerExit(120);                            // механик при Т3 снял 120
        when(masterData.findVehicle("9911QA01")).thenReturn(Optional.of(Map.of("odometer", 5000))); // карточка уже 5000

        Waybill r = service.activate(w.getId(), DISP, null);

        assertThat(r.getOdometerExit()).isEqualTo(5000);
        assertThat(r.getStatus()).isEqualTo(WaybillStatus.ACTIVE);
        WaybillTitle t4 = savedTitles.get(savedTitles.size() - 1);
        assertThat(t4.getTitleType()).isEqualTo("T4");
        assertThat(t4.getData()).containsEntry("dispatcher", "Назарова Мунира");
    }

    @Test
    @DisplayName("выезд: показание механика сохраняется, если оно больше пробега в справочнике; справочник недоступен — не блокирует")
    void mechanicReadingKeptWhenHigher() {
        Waybill w = waybill(WaybillStatus.ISSUED, 100);
        w.setOdometerExit(150);
        when(masterData.findVehicle("9911QA01")).thenThrow(new RuntimeException("master-data down"));

        assertThat(service.activate(w.getId(), DISP, null).getOdometerExit()).isEqualTo(150);
    }

    @Test
    @DisplayName("возврат: в данных Т5 — Ф.И.О. диспетчера (для отметки на бланке)")
    void returnTitleCarriesDispatcherName() {
        Waybill w = waybill(WaybillStatus.ACTIVE, 0);
        w.setOdometerExit(100);

        service.returnTrip(w.getId(), DISP, 260);

        WaybillTitle t5 = savedTitles.get(savedTitles.size() - 1);
        assertThat(t5.getTitleType()).isEqualTo("T5");
        assertThat(t5.getData()).containsEntry("dispatcher", "Назарова Мунира").containsEntry("distance", 160);
    }

    @Test
    @DisplayName("закрытие: справочник отказался уменьшать пробег (400) — лист всё равно закрыт, расхождение в истории")
    void closeSucceedsWhenRegistryRefusesLowerOdometer() {
        Waybill w = waybill(WaybillStatus.RETURNED, 0);
        w.setOdometerExit(100);
        w.setOdometerEntry(260);
        when(titles.existsByWaybillIdAndTitleType(w.getId(), "T6")).thenReturn(true);
        doThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", null, null, null))
                .when(masterData).updateVehicleOdometer(anyString(), anyInt());

        Waybill r = service.close(w.getId(), DISP);

        assertThat(r.getStatus()).isEqualTo(WaybillStatus.COMPLETED);
        assertThat(savedEvents.get(savedEvents.size() - 1).getReason()).contains("не изменён");
    }

    @Test
    @DisplayName("закрытие пассажирского листа с двумя Т6 проходит (раньше — 409 «Неоднозначные данные»)")
    void closeWithRepeatedPostTripExam() {
        Waybill w = waybill(WaybillStatus.RETURNED, 0);
        w.setOdometerExit(0);
        w.setOdometerEntry(160);
        when(titles.existsByWaybillIdAndTitleType(w.getId(), "T6")).thenReturn(true);
        when(titles.findByWaybillIdAndTitleType(eq(w.getId()), eq("T6")))
                .thenThrow(new org.springframework.dao.IncorrectResultSizeDataAccessException(1, 2));

        assertThat(service.close(w.getId(), DISP).getStatus()).isEqualTo(WaybillStatus.COMPLETED);
    }

    @Test
    @DisplayName("пассажирский лист без Т6 не закрывается — понятный 409")
    void closeWithoutPostTripExam() {
        Waybill w = waybill(WaybillStatus.RETURNED, 0);
        when(titles.existsByWaybillIdAndTitleType(w.getId(), "T6")).thenReturn(false);

        assertThatThrownBy(() -> service.close(w.getId(), DISP))
                .isInstanceOf(ConflictException.class).hasMessageContaining("послерейсовый медосмотр");
    }

    @Test
    @DisplayName("второй Т6 по тому же листу отклоняется (409), а не плодит дубль")
    void secondPostTripExamRejected() {
        Waybill w = waybill(WaybillStatus.RETURNED, 0);
        when(masterData.findEmployee("111111111")).thenReturn(Optional.of(Map.of(
                "rma", "111111111", "type", 1, "name", "Врач", "organizationId", ORG_ID)));
        when(titles.existsByWaybillIdAndTitleType(w.getId(), "T6")).thenReturn(true);

        assertThatThrownBy(() -> service.confirmMed(w.getId(), "111111111", true, Map.of("pulse", 70)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("уже проведён");
    }
}
