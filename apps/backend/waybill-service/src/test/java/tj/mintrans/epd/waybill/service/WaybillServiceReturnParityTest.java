package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import tj.mintrans.epd.waybill.client.MasterDataClient;
import tj.mintrans.epd.waybill.config.CurrentUser;
import tj.mintrans.epd.waybill.config.TenantScope;
import tj.mintrans.epd.waybill.crypto.MedicalDataCrypto;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.domain.WaybillStatusEvent;
import tj.mintrans.epd.waybill.domain.WaybillTitle;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.domain.WorkDay;
import tj.mintrans.epd.waybill.repository.WaybillInspectionRepository;
import tj.mintrans.epd.waybill.repository.WaybillPaymentRepository;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.repository.WaybillStatusEventRepository;
import tj.mintrans.epd.waybill.repository.WaybillTitleRepository;
import tj.mintrans.epd.waybill.repository.WorkDayRepository;
import tj.mintrans.epd.waybill.signing.TitleSigner;
import tj.mintrans.epd.waybill.web.error.ApiErrors.ConflictException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.ForbiddenException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Возврат (Т5) по legacy-правилам, найденным сверкой 25.09.2026:
 * предел пробега за лист ({@code valid_counter_value}), круги/выручка/гашти ибтидоӣ листа без рабочих
 * дней (вкладка «коркард» 1-АД), возврат просроченного на линии листа, подпись своим РМА диспетчера,
 * пробег ТС в справочнике — сразу при возврате.
 */
class WaybillServiceReturnParityTest {

    private static final String ORG_ID = "11111111-1111-1111-1111-111111111111";
    private static final String DISP = "333333333";

    private final WaybillRepository waybills = mock(WaybillRepository.class);
    private final WaybillTitleRepository titles = mock(WaybillTitleRepository.class);
    private final WaybillStatusEventRepository events = mock(WaybillStatusEventRepository.class);
    private final MasterDataClient masterData = mock(MasterDataClient.class);
    private final TenantScope tenant = mock(TenantScope.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final TitleSigner signer = mock(TitleSigner.class);
    private final WorkDayRepository workDays = mock(WorkDayRepository.class);
    private final List<WorkDay> savedDays = new ArrayList<>();
    private final List<WaybillStatusEvent> savedEvents = new ArrayList<>();
    private WaybillService service;

    @BeforeEach
    void setUp() {
        service = new WaybillService(waybills, titles, events, mock(WaybillPaymentRepository.class), masterData,
                mock(WaybillNumberGenerator.class), mock(BranchSerialGenerator.class),
                mock(ApplicationEventPublisher.class), tenant, currentUser,
                mock(WaybillInspectionRepository.class), signer, mock(MedicalDataCrypto.class), false, BigDecimal.TEN,
                workDays, false);
        when(tenant.isBounded()).thenReturn(false);
        when(waybills.save(any())).thenAnswer(i -> i.getArgument(0));
        when(titles.save(any())).thenAnswer(i -> i.getArgument(0));
        when(events.save(any())).thenAnswer(i -> { savedEvents.add(i.getArgument(0)); return i.getArgument(0); });
        when(workDays.save(any())).thenAnswer(i -> { savedDays.add(i.getArgument(0)); return i.getArgument(0); });
        when(signer.sign(any(), anyString(), anyString(), any())).thenReturn("sig");
        when(masterData.findEmployee(DISP)).thenReturn(Optional.of(Map.of(
                "rma", DISP, "type", 3, "name", "Назарова Мунира", "organizationId", ORG_ID)));
    }

    private Waybill waybill(WaybillType type, WaybillStatus status, int validityDays) {
        Waybill w = new Waybill();
        org.springframework.test.util.ReflectionTestUtils.setField(w, "id", UUID.randomUUID());
        w.setWaybillType(type);
        w.setStatus(status);
        w.setOrganizationRma("025680800");
        w.setVehicleRegNumber("9911QA01");
        w.setDriverRma("555555555");
        w.setOrganizationSnapshot(Map.of("id", ORG_ID));
        w.setVehicleSnapshot(new java.util.HashMap<>(Map.of("id", "22222222-2222-2222-2222-222222222222")));
        var from = OffsetDateTime.of(2026, 9, 20, 7, 0, 0, 0, ZoneOffset.UTC);
        w.setValidFrom(from);
        w.setValidTo(from.plusDays(validityDays));
        w.setOdometerExit(1000);
        when(waybills.findByIdForUpdate(w.getId())).thenReturn(Optional.of(w));
        return w;
    }

    @Test
    @DisplayName("пределы пробега за лист — как legacy valid_counter_value")
    void maxTripKmPerForm() {
        assertThat(WaybillType.WB_BUS.maxTripKm(1)).isEqualTo(600);
        assertThat(WaybillType.WB_TROLLEYBUS.maxTripKm(1)).isEqualTo(215);
        assertThat(WaybillType.WB_MINIBUS.maxTripKm(4)).isEqualTo(1600);
        assertThat(WaybillType.WB_TAXI.maxTripKm(7)).isEqualTo(2800);
        assertThat(WaybillType.WB_CAR.maxTripKm(30)).isEqualTo(12000);   // 3-С «30»
        assertThat(WaybillType.WB_TRUCK.maxTripKm(15)).isZero();
        assertThat(WaybillType.WB_CAR.legalMaxValidityDays()).isEqualTo(30);
        assertThat(WaybillType.WB_BUS.legalMaxValidityDays()).isEqualTo(1);
    }

    @Test
    @DisplayName("автобус: 601 км за лист → 422, ровно 600 — принимается")
    void busTripLimit() {
        Waybill w = waybill(WaybillType.WB_BUS, WaybillStatus.ACTIVE, 1);
        assertThatThrownBy(() -> service.returnTrip(w.getId(), DISP, 1601))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("601").hasMessageContaining("600");
        assertThatCode(() -> service.returnTrip(w.getId(), DISP, 1600)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("3-С на 30 дней: предел 12 000 км (3-С «30»), на 7 дней — 2 800")
    void car30Limit() {
        Waybill w7 = waybill(WaybillType.WB_CAR, WaybillStatus.ACTIVE, 7);
        assertThatThrownBy(() -> service.returnTrip(w7.getId(), DISP, 1000 + 2801))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("2800");
        Waybill w30 = waybill(WaybillType.WB_CAR, WaybillStatus.ACTIVE, 30);
        assertThatCode(() -> service.returnTrip(w30.getId(), DISP, 1000 + 11000)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("1-АД без рабочих дней: круги, выручка и гашти ибтидоӣ при возврате сохраняются рабочим днём")
    void dayDataAtReturnBecomesWorkDay() {
        Waybill w = waybill(WaybillType.WB_BUS, WaybillStatus.ACTIVE, 1);
        var t4 = new WaybillTitle();
        org.springframework.test.util.ReflectionTestUtils.setField(t4, "titleType", "T4");
        org.springframework.test.util.ReflectionTestUtils.setField(t4, "signedAt",
                OffsetDateTime.of(2026, 9, 20, 6, 30, 0, 0, ZoneOffset.UTC));
        when(titles.findFirstByWaybillIdAndTitleTypeOrderBySignedAtDesc(w.getId(), "T4")).thenReturn(Optional.of(t4));
        when(workDays.countByWaybillId(w.getId())).thenReturn(0L);

        service.returnTrip(w.getId(), DISP, 1180, null, new WaybillService.ReturnMetrics(null, null, null, null, null, null,
                12, new BigDecimal("350.50"), "begin_path_a", "begin_path_a"));

        assertThat(savedDays).hasSize(1);
        WorkDay d = savedDays.getFirst();
        assertThat(d.getLaps()).isEqualTo(12);
        assertThat(d.getRevenue()).isEqualByComparingTo("350.50");
        assertThat(d.getBeginPathA()).isEqualTo("begin_path_a");
        assertThat(d.getBeginPathB()).isEqualTo("begin_path_a");
        assertThat(d.getOdometerExit()).isEqualTo(1000);
        assertThat(d.getOdometerEntry()).isEqualTo(1180);
        assertThat(d.getWorkDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 20));
        assertThat(d.getExitTime()).isEqualTo(java.time.LocalTime.of(6, 30));
        // пробег ТС в справочнике — сразу при возврате, а не только при закрытии
        verify(masterData).updateVehicleOdometer("22222222-2222-2222-2222-222222222222", 1180);
    }

    @Test
    @DisplayName("круги при возврате, если дни уже есть, — 422; неверный селектор гашти — 422")
    void dayDataRejectedWhenDaysExist() {
        Waybill w = waybill(WaybillType.WB_MINIBUS, WaybillStatus.ACTIVE, 4);
        when(workDays.countByWaybillId(w.getId())).thenReturn(2L);
        assertThatThrownBy(() -> service.returnTrip(w.getId(), DISP, 1100, null,
                new WaybillService.ReturnMetrics(null, null, null, null, null, null, 5, null, null, null)))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("по дням");
        assertThatThrownBy(() -> service.returnTrip(w.getId(), DISP, 1100, null,
                new WaybillService.ReturnMetrics(null, null, null, null, null, null, null, null, "begin_path_c", null)))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("begin_path_a");
    }

    @Test
    @DisplayName("просроченный на линии (EXPIRED после Т4, без Т5) возвращается; без выезда — 409")
    void overdueOnLineCanReturn() {
        Waybill w = waybill(WaybillType.WB_TAXI, WaybillStatus.EXPIRED, 7);
        when(titles.existsByWaybillIdAndTitleType(w.getId(), "T4")).thenReturn(true);
        when(titles.existsByWaybillIdAndTitleType(w.getId(), "T5")).thenReturn(false);

        Waybill r = service.returnTrip(w.getId(), DISP, 1500);

        assertThat(r.getStatus()).isEqualTo(WaybillStatus.RETURNED);
        assertThat(savedEvents.getLast().getReason()).contains("после истечения");

        Waybill never = waybill(WaybillType.WB_TAXI, WaybillStatus.EXPIRED, 7);
        when(titles.existsByWaybillIdAndTitleType(never.getId(), "T4")).thenReturn(false);
        assertThatThrownBy(() -> service.returnTrip(never.getId(), DISP, 1500)).isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("диспетчер подписывает только своим РМА; администратор — указанным")
    void dispatcherSignsWithOwnRma() {
        Waybill w = waybill(WaybillType.WB_BUS, WaybillStatus.ACTIVE, 1);
        when(currentUser.hasRole("DISPATCHER")).thenReturn(true);
        when(currentUser.rma()).thenReturn(Optional.of("444444444"));
        assertThatThrownBy(() -> service.returnTrip(w.getId(), DISP, 1100))
                .isInstanceOf(ForbiddenException.class).hasMessageContaining("444444444");

        when(currentUser.rma()).thenReturn(Optional.of(DISP));
        assertThatCode(() -> service.returnTrip(w.getId(), DISP, 1100)).doesNotThrowAnyException();
    }
}
