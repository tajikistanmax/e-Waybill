package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tj.mintrans.epd.waybill.calc.WaybillCalcAssembler;
import tj.mintrans.epd.waybill.calc.report.PassengerVolumeTrend;
import tj.mintrans.epd.waybill.client.MasterDataClient;
import tj.mintrans.epd.waybill.config.CurrentUser;
import tj.mintrans.epd.waybill.config.TenantScope;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.repository.WaybillPlanRepository;
import tj.mintrans.epd.waybill.repository.WaybillRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIGRATION.md §6.8 — тренд панели: число выписанных ПЛ по месяцам (legacy
 * {@code Ebus\BillCountsController}: COUNT по MONTH(created_at), все статусы) и выбор вида
 * (автобус / троллейбус / оба). Оборот здесь не проверяется (поток COMPLETED пуст).
 */
class RegionalReportServiceTrendTest {

    private WaybillRepository waybills;
    private TenantScope tenant;
    private RegionalReportService service;

    @BeforeEach
    void setUp() {
        WaybillPeriodScan scan = mock(WaybillPeriodScan.class);            // forEachCompleted — no-op
        waybills = mock(WaybillRepository.class);
        tenant = mock(TenantScope.class);
        lenient().when(tenant.isBounded()).thenReturn(false);
        service = new RegionalReportService(scan, mock(WaybillPlanRepository.class),
                mock(WaybillCalcAssembler.class), mock(CurrentUser.class), tenant,
                mock(MasterDataClient.class), waybills);
    }

    private static String key(LocalDate d) {
        return String.format("%04d-%02d", d.getYear(), d.getMonthValue());
    }

    @Test
    @DisplayName("7 точек от старого к новому; счётчики из группировки БД ложатся в свои месяцы, остальные 0")
    void countsPerMonth() {
        LocalDate now = LocalDate.now();
        LocalDate prev = now.minusMonths(1);
        when(waybills.countByMonth(anyCollection(), any(), any())).thenReturn(List.of(
                new Object[]{now.getYear(), now.getMonthValue(), 12L},
                new Object[]{prev.getYear(), prev.getMonthValue(), 5L},
                new Object[]{2000, 1, 999L}));                             // вне окна — игнорируется

        PassengerVolumeTrend t = service.passengerVolumeTrend(7);

        assertThat(t.months()).isEqualTo(7);
        assertThat(t.types()).containsExactly(WaybillType.WB_BUS, WaybillType.WB_TROLLEYBUS);
        assertThat(t.points()).hasSize(7);
        assertThat(t.points().getLast().month()).isEqualTo(key(now));
        assertThat(t.points().getLast().waybills()).isEqualTo(12L);
        assertThat(t.points().get(5).waybills()).isEqualTo(5L);
        assertThat(t.points().getFirst().waybills()).isZero();
        assertThat(t.points()).allSatisfy(p -> assertThat(p.turnoverMillion()).isZero());
    }

    @Test
    @DisplayName("?type=WB_TROLLEYBUS — в БД уходит только троллейбус (legacy dashboard/ebus)")
    @SuppressWarnings("unchecked")
    void typeFilterReachesQuery() {
        when(waybills.countByMonth(anyCollection(), any(), any())).thenReturn(List.of());

        PassengerVolumeTrend t = service.passengerVolumeTrend(3, Set.of(WaybillType.WB_TROLLEYBUS));

        ArgumentCaptor<Collection<WaybillType>> types = ArgumentCaptor.forClass(Collection.class);
        verify(waybills).countByMonth(types.capture(), any(), any());
        assertThat(types.getValue()).containsExactly(WaybillType.WB_TROLLEYBUS);
        assertThat(t.types()).containsExactly(WaybillType.WB_TROLLEYBUS);
        assertThat(t.points()).hasSize(3);
    }

    @Test
    @DisplayName("тенант: счётчики только по его организациям; пустая область — запросов в БД нет")
    void tenantScope() {
        when(tenant.isBounded()).thenReturn(true);
        when(tenant.rmas()).thenReturn(Set.of("025680800"));
        when(waybills.countByMonthForOrganizations(anyCollection(), anyCollection(), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{LocalDate.now().getYear(), LocalDate.now().getMonthValue(), 3L}));

        PassengerVolumeTrend t = service.passengerVolumeTrend(2);
        assertThat(t.points().getLast().waybills()).isEqualTo(3L);
        verify(waybills, never()).countByMonth(anyCollection(), any(), any());

        when(tenant.rmas()).thenReturn(Set.of("__none__"));
        PassengerVolumeTrend none = service.passengerVolumeTrend(2);
        assertThat(none.points()).allSatisfy(p -> assertThat(p.waybills()).isZero());
    }
}
