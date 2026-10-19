package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import tj.mintrans.epd.waybill.client.MasterDataClient;
import tj.mintrans.epd.waybill.config.CurrentUser;
import tj.mintrans.epd.waybill.config.TenantScope;
import tj.mintrans.epd.waybill.crypto.MedicalDataCrypto;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.repository.WaybillInspectionRepository;
import tj.mintrans.epd.waybill.repository.WaybillPaymentRepository;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.repository.WaybillStatusEventRepository;
import tj.mintrans.epd.waybill.repository.WaybillTitleRepository;
import tj.mintrans.epd.waybill.repository.WorkDayRepository;
import tj.mintrans.epd.waybill.signing.TitleSigner;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Новый тип ПЛ «Мотоцикл (аренда)» (WB_MOTORCYCLE, форма 3-М, нац.код 11, вид ТС 7).
 * Проверяем валидацию type_data (объём двигателя), необязательность маршрута и
 * привязку вида ТС/категории ВУ. Валидация достигается через updateDraft (лёгкий путь
 * к приватному validateTypeData — не требует обвязки мастер-данных как create).
 */
class WaybillServiceMotorcycleTest {

    private static final String ORG_ID = "11111111-1111-1111-1111-111111111111";

    private final WaybillRepository waybills = mock(WaybillRepository.class);
    private final WaybillTitleRepository titles = mock(WaybillTitleRepository.class);
    private final WaybillStatusEventRepository events = mock(WaybillStatusEventRepository.class);
    private final MasterDataClient masterData = mock(MasterDataClient.class);
    private final TenantScope tenant = mock(TenantScope.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final TitleSigner signer = mock(TitleSigner.class);
    private final MedicalDataCrypto crypto = mock(MedicalDataCrypto.class);
    private WaybillService service;

    @BeforeEach
    void setUp() {
        service = new WaybillService(waybills, titles, events, mock(WaybillPaymentRepository.class), masterData,
                mock(WaybillNumberGenerator.class), mock(BranchSerialGenerator.class),
                mock(ApplicationEventPublisher.class), tenant, currentUser,
                mock(WaybillInspectionRepository.class), signer, crypto, false, BigDecimal.TEN,
                mock(WorkDayRepository.class), false);
        when(tenant.isBounded()).thenReturn(false);
        when(waybills.save(any())).thenAnswer(i -> i.getArgument(0));
        when(events.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    /** Черновик мото-ПЛ без маршрута (аренда — свободное перемещение). */
    private Waybill draftMoto() {
        Waybill w = new Waybill();
        ReflectionTestUtils.setField(w, "id", UUID.randomUUID());
        w.setWaybillType(WaybillType.WB_MOTORCYCLE);
        w.setStatus(WaybillStatus.DRAFT);
        w.setOrganizationRma("025680800");
        w.setVehicleRegNumber("1234AB01");
        w.setDriverRma("555555555");
        w.setOrganizationSnapshot(Map.of("id", ORG_ID));
        when(waybills.findByIdForUpdate(w.getId())).thenReturn(Optional.of(w));
        return w;
    }

    @Test
    @DisplayName("мото: корректный engineVolume и без маршрута — валидация проходит, type_data сохраняется")
    void validMotorcyclePassesWithoutRoute() {
        Waybill w = draftMoto();
        Waybill r = service.updateDraft(w.getId(), null, null, null,
                Map.of("engineVolume", 650, "rentalContract", "АР-2026/17"), "disp");
        assertThat(r.getRoute()).isNull();
        assertThat(r.getTypeData()).containsEntry("engineVolume", 650);
        assertThat(r.getTypeData()).containsEntry("rentalContract", "АР-2026/17");
    }

    @Test
    @DisplayName("мото: engineVolume = 0 — ошибка диапазона")
    void engineVolumeZeroRejected() {
        Waybill w = draftMoto();
        assertThatThrownBy(() -> service.updateDraft(w.getId(), null, null, null,
                Map.of("engineVolume", 0), "disp"))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("1–3000");
    }

    @Test
    @DisplayName("мото: engineVolume > 3000 — ошибка диапазона")
    void engineVolumeTooLargeRejected() {
        Waybill w = draftMoto();
        assertThatThrownBy(() -> service.updateDraft(w.getId(), null, null, null,
                Map.of("engineVolume", 5000), "disp"))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("1–3000");
    }

    @Test
    @DisplayName("мото: engineVolume не число — ошибка формата")
    void engineVolumeNonNumericRejected() {
        Waybill w = draftMoto();
        assertThatThrownBy(() -> service.updateDraft(w.getId(), null, null, null,
                Map.of("engineVolume", "abc"), "disp"))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("engineVolume");
    }

    @Test
    @DisplayName("мото: engineVolume опционален — пустой type_data валиден")
    void engineVolumeOptional() {
        Waybill w = draftMoto();
        Waybill r = service.updateDraft(w.getId(), null, null, null, Map.of(), "disp");
        assertThat(r.getStatus()).isEqualTo(WaybillStatus.DRAFT);
    }

    @Test
    @DisplayName("мото: вид ТС ограничен {7}, категория ВУ для вида 7 — A")
    void transportTypeAndLicense() {
        @SuppressWarnings("unchecked")
        Set<Integer> allowed = (Set<Integer>) ReflectionTestUtils.invokeMethod(
                service, "allowedTransportTypes", WaybillType.WB_MOTORCYCLE);
        assertThat(allowed).containsExactly(7);
        assertThat(WaybillService.requiredLicenseCategories(7)).contains("A");
    }
}
