package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.config.TenantScope;
import tj.mintrans.epd.waybill.domain.GpsEvent;
import tj.mintrans.epd.waybill.domain.GpsEventState;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.repository.GpsEventRepository;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MIGRATION.md 9.7 — регистрация GPS-события: ПЛ дня, направление в правиле повтора, ответ выезда из предприятия. */
class GpsEventServiceTest {

    private final GpsEventRepository events = mock(GpsEventRepository.class);
    private final WaybillRepository waybills = mock(WaybillRepository.class);
    private final GpsEventService service = new GpsEventService(events, waybills, mock(TenantScope.class), 20, "WB_BUS,WB_TROLLEYBUS");

    private Waybill busOfToday() {
        Waybill wb = mock(Waybill.class);
        when(wb.getId()).thenReturn(UUID.randomUUID());
        when(wb.getOrganizationRma()).thenReturn("025680800");
        when(wb.getOrganizationSnapshot()).thenReturn(Map.of("name", "KVD Avtobusi"));
        when(wb.getDriverRma()).thenReturn("461930031");
        when(wb.getDriverSnapshot()).thenReturn(Map.of("fullName", "Akhmedzoda Z."));
        when(wb.getRoute()).thenReturn("3");
        when(wb.getSchedule()).thenReturn("1");
        when(wb.getValidFrom()).thenReturn(OffsetDateTime.now().withHour(6).withMinute(30));
        when(wb.getWaybillType()).thenReturn(WaybillType.WB_BUS);
        return wb;
    }

    @Test
    void noWaybillOfTodayIsRejectedAsInLegacy() {
        when(waybills.findFirstByVehicleRegNumberAndWaybillTypeInAndSourceNotAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                anyString(), any(), anyString(), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.register(new GpsEventService.Request("0114tj01", GpsEventState.EXIT_FROM_COMPANY, null, null, null)))
                .isInstanceOf(UnprocessableException.class)
                .hasMessage("Путевой лист для данного транспортного средства не найден.");
        verify(events, never()).save(any());
    }

    @Test
    void routeStateUsesDirectionAwareCooldownAndExitFromCompanyReturnsWaybillInfo() {
        Waybill wb = busOfToday();
        when(waybills.findFirstByVehicleRegNumberAndWaybillTypeInAndSourceNotAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                eq("0114TJ01"), eq(Set.of(WaybillType.WB_BUS, WaybillType.WB_TROLLEYBUS)), eq("MIGRATED"), any()))
                .thenReturn(Optional.of(wb));
        when(events.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // Въезд на маршрут по направлению B 5 минут назад — но регистрируем направление A: повтор не считается.
        GpsEvent recentB = new GpsEvent();
        recentB.setEventTime(OffsetDateTime.now().minusMinutes(5));
        when(events.findFirstByVehicleRegNumberAndStateAndDirectionAndEventTimeGreaterThanEqualOrderByEventTimeDesc(
                eq("0114TJ01"), eq(GpsEventState.ENTER_INTO_ROUTE), eq("B"), any())).thenReturn(Optional.of(recentB));
        when(events.findFirstByVehicleRegNumberAndStateAndDirectionAndEventTimeGreaterThanEqualOrderByEventTimeDesc(
                eq("0114TJ01"), eq(GpsEventState.ENTER_INTO_ROUTE), eq("A"), any())).thenReturn(Optional.empty());

        var r = service.register(new GpsEventService.Request(" 0114tj01 ", GpsEventState.ENTER_INTO_ROUTE, "а", null, null));
        assertThat(r.success()).isTrue();
        assertThat(r.event().getDirection()).isEqualTo("A");
        assertThat(r.event().getOrganizationRma()).isEqualTo("025680800");
        assertThat(r.event().getDriverName()).isEqualTo("Akhmedzoda Z.");
        assertThat(r.event().getRoute()).isEqualTo("3");
        assertThat(r.routeNumber()).isNull();

        // То же направление B — отказ по повтору.
        assertThatThrownBy(() -> service.register(new GpsEventService.Request("0114TJ01", GpsEventState.ENTER_INTO_ROUTE, "B", null, null)))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("через 20 минут");

        // Выезд из предприятия — ответ с маршрутом/графиком/временем выезда ПЛ (legacy route_number/schedule/exit_time).
        when(events.findFirstByVehicleRegNumberAndStateAndEventTimeGreaterThanEqualOrderByEventTimeDesc(
                eq("0114TJ01"), eq(GpsEventState.EXIT_FROM_COMPANY), any())).thenReturn(Optional.empty());
        var out = service.register(new GpsEventService.Request("0114TJ01", GpsEventState.EXIT_FROM_COMPANY, null, null, null));
        assertThat(out.routeNumber()).isEqualTo("3");
        assertThat(out.schedule()).isEqualTo("1");
        assertThat(out.exitTime()).matches("\\d{2}:\\d{2}");
        assertThat(out.event().getDirection()).isNull();
    }

    @Test
    void enterIntoCompanyRequiresDistance() {
        assertThatThrownBy(() -> service.register(new GpsEventService.Request("0114TJ01", GpsEventState.ENTER_INTO_COMPANY, null, null, null)))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("distance");
        assertThat(GpsEventService.parseTypes("wb_minibus, WB_BUS")).containsExactlyInAnyOrder(WaybillType.WB_MINIBUS, WaybillType.WB_BUS);
        assertThat(GpsEventService.parseTypes("")).containsExactlyInAnyOrder(WaybillType.WB_BUS, WaybillType.WB_TROLLEYBUS);
        assertThat(new GpsEventService(events, waybills, mock(TenantScope.class), 20, "WB_BUS").cooldownMinutes()).isEqualTo(20);
        assertThat(new BigDecimal("0").signum()).isZero();
    }
}
