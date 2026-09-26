package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.client.MasterDataClient;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.repository.WaybillStatusEventRepository;
import tj.mintrans.epd.waybill.web.error.ApiErrors;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Сверка 25.09, G3: повторный запрос агрегатора по действующему листу возвращает этот лист (как legacy
 * {@code waybill_neru}), а не аннулирует его и не создаёт новый; новая заявка — только на сегодня.
 */
class AggregatorServiceSubmitTest {

    private WaybillRepository waybills;
    private WaybillService waybillService;
    private AggregatorService service;

    @BeforeEach
    void setUp() {
        waybills = mock(WaybillRepository.class);
        waybillService = mock(WaybillService.class);
        MasterDataClient masterData = mock(MasterDataClient.class);
        when(masterData.findOrganization("025680800")).thenReturn(Optional.of(Map.of("id", "7", "rma", "025680800")));
        when(masterData.findVehicle("0114TJ01")).thenReturn(Optional.of(Map.of("id", "9", "odometer", 100)));
        when(masterData.findDriver("461930031")).thenReturn(Optional.of(Map.of("id", "3")));
        service = new AggregatorService(waybills, mock(WaybillStatusEventRepository.class), masterData, waybillService);
    }

    private Waybill current(boolean med, boolean tech) {
        var wb = new Waybill();
        wb.setSource("AGGREGATOR");
        wb.setStatus(WaybillStatus.ISSUED);
        wb.setValidFrom(OffsetDateTime.now().minusHours(2));
        wb.setValidTo(OffsetDateTime.now().plusHours(8));
        wb.setMedPassed(med);
        wb.setTechPassed(tech);
        when(waybills.findFirstByOrganizationRmaAndVehicleRegNumberAndDriverRmaAndSourceAndStatusInOrderByCreatedAtDesc(
                eq("025680800"), eq("0114TJ01"), eq("461930031"), eq("AGGREGATOR"), any()))
                .thenReturn(Optional.of(wb));
        return wb;
    }

    private AggregatorService.Submission submit(OffsetDateTime exit) {
        return service.submit("025680800", "0114TJ01", "461930031", null, exit, exit.plusHours(12), 50);
    }

    @Test
    @DisplayName("действующий лист без осмотров — 409 «Доктор не подтвердил путёвку», новая заявка не создаётся")
    void pendingWaybillIsNotRecreated() {
        current(false, false);
        assertThatThrownBy(() -> submit(OffsetDateTime.now()))
                .isInstanceOf(ApiErrors.ConflictException.class)
                .hasMessage("Доктор не подтвердил путёвку");
        verify(waybills, never()).save(any());
        verify(waybillService, never()).cancel(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("действующий подтверждённый лист возвращается как есть (200)")
    void confirmedWaybillIsReturned() {
        var wb = current(true, true);
        var result = submit(OffsetDateTime.now());
        assertThat(result.created()).isFalse();
        assertThat(result.waybill()).isSameAs(wb);
    }

    @Test
    @DisplayName("нет действующего — дата выезда только сегодняшняя (422 exit_date)")
    void newRequestOnlyForToday() {
        assertThatThrownBy(() -> submit(OffsetDateTime.now().plusDays(1)))
                .isInstanceOf(ApiErrors.FieldException.class)
                .hasMessage("Дата выезда должна быть сегодняшней")
                .extracting(e -> ((ApiErrors.FieldException) e).field()).isEqualTo("exit_date");
    }

    @Test
    @DisplayName("лист в силе — со дня выезда по день въезда включительно")
    void inForceByDays() {
        var wb = new Waybill();
        wb.setValidFrom(OffsetDateTime.now().minusDays(2));
        wb.setValidTo(OffsetDateTime.now().minusDays(1));
        assertThat(AggregatorService.inForce(wb, OffsetDateTime.now())).isFalse();
        wb.setValidTo(OffsetDateTime.now());
        assertThat(AggregatorService.inForce(wb, OffsetDateTime.now())).isTrue();
    }
}
