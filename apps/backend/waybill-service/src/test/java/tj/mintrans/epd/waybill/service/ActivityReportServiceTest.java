package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tj.mintrans.epd.waybill.config.TenantScope;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIGRATION.md §8.5/§8.6 — активность ТС/водителей за период: агрегат по видам ПЛ, область тенанта,
 * сортировка по ключу; пустые виды = все виды.
 */
class ActivityReportServiceTest {

    private final WaybillRepository waybills = mock(WaybillRepository.class);
    private final TenantScope tenant = mock(TenantScope.class);
    private final ActivityReportService service = new ActivityReportService(waybills, tenant);

    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    @Test
    @DisplayName("тенант: счётчики по своей области, только выбранные виды (3-С = WB_CAR + WB_TAXI), null-ключи отброшены")
    @SuppressWarnings("unchecked")
    void tenantScopedByVehicle() {
        when(tenant.isBounded()).thenReturn(true);
        when(tenant.rmas()).thenReturn(Set.of("025680800"));
        when(waybills.countByVehicleForOrganizations(anyCollection(), anyCollection(), any(), any()))
                .thenReturn(List.of(new Object[]{"2222TJ01", 4L}, new Object[]{"0114TJ01", 12L}, new Object[]{null, 3L}));

        List<ActivityReportService.Row> rows = service.activity(ActivityReportService.By.VEHICLE, FROM, TO,
                List.of(WaybillType.WB_CAR, WaybillType.WB_TAXI), "999999999");

        assertThat(rows).extracting(ActivityReportService.Row::key).containsExactly("0114TJ01", "2222TJ01");
        assertThat(rows.get(0).waybills()).isEqualTo(12L);
        ArgumentCaptor<Collection<String>> orgs = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Collection<WaybillType>> types = ArgumentCaptor.forClass(Collection.class);
        verify(waybills).countByVehicleForOrganizations(orgs.capture(), types.capture(), any(), any());
        assertThat(orgs.getValue()).containsExactly("025680800");                 // запрошенная чужая org проигнорирована
        assertThat(types.getValue()).containsExactlyInAnyOrder(WaybillType.WB_CAR, WaybillType.WB_TAXI);
        verify(waybills, never()).countByVehicle(anyCollection(), any(), any());
    }

    @Test
    @DisplayName("платформенная роль без организации: все организации и все виды; by=DRIVER")
    @SuppressWarnings("unchecked")
    void platformAllDrivers() {
        when(tenant.isBounded()).thenReturn(false);
        when(waybills.countByDriver(anyCollection(), any(), any())).thenReturn(List.<Object[]>of(new Object[]{"461930031", 7L}));

        List<ActivityReportService.Row> rows = service.activity(ActivityReportService.By.DRIVER, FROM, TO, null, null);

        assertThat(rows).containsExactly(new ActivityReportService.Row("461930031", 7L));
        ArgumentCaptor<Collection<WaybillType>> types = ArgumentCaptor.forClass(Collection.class);
        verify(waybills).countByDriver(types.capture(), any(), any());
        assertThat(types.getValue()).hasSize(WaybillType.values().length);
    }

    @Test
    @DisplayName("период from > to и пустая область тенанта")
    void validationAndEmptyScope() {
        when(tenant.isBounded()).thenReturn(false);
        assertThatThrownBy(() -> service.activity(ActivityReportService.By.VEHICLE, TO, FROM, null, null))
                .isInstanceOf(UnprocessableException.class);

        when(tenant.isBounded()).thenReturn(true);
        when(tenant.rmas()).thenReturn(Set.of("__none__"));
        assertThat(service.activity(ActivityReportService.By.VEHICLE, FROM, TO, null, null)).isEmpty();
        verify(waybills, never()).countByVehicleForOrganizations(anyCollection(), anyCollection(), any(), any());
    }
}
