package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.calc.WaybillCalcAssembler;
import tj.mintrans.epd.waybill.calc.model.CargoCalcResult;
import tj.mintrans.epd.waybill.calc.model.CoefficientBreakdown;
import tj.mintrans.epd.waybill.calc.model.DriverSalary;
import tj.mintrans.epd.waybill.calc.model.FuelConsumption;
import tj.mintrans.epd.waybill.calc.report.ReportType;
import tj.mintrans.epd.waybill.calc.report.WaybillReport;
import tj.mintrans.epd.waybill.config.TenantScope;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.domain.WaybillType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Сверка 25.09.2026 (D1, D2, D6, п.38): бланк типового отчёта, грузовые колонки P / Z / дни / часы,
 * знак «фарқият = норма − выдано», выбор своей организации перевозчиком.
 */
class WaybillReportServiceBillTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);
    private static int seq = 0;

    private static Waybill wb(WaybillType type, String org) {
        Waybill w = new Waybill();
        w.setWaybillType(type);
        w.setOrganizationRma(org);
        w.setVehicleRegNumber("0114TJ01");
        w.setDriverRma("461930031");
        w.setOdometerExit(1000);
        w.setOdometerEntry(1100);
        w.setStatus(WaybillStatus.COMPLETED);
        w.setNumber("01-26-04-%07d-0".formatted(++seq));
        return w;
    }

    private WaybillReportService service(List<Waybill> period, WaybillCalcAssembler.View view, TenantScope tenant,
                                         java.util.List<Set<String>> scopes) {
        WaybillPeriodScan scan = mock(WaybillPeriodScan.class);
        WaybillCalcAssembler assembler = mock(WaybillCalcAssembler.class);
        lenient().when(assembler.calculate(any(), any())).thenReturn(view);
        doAnswer(inv -> {
            scopes.add(inv.getArgument(2));
            Consumer<Waybill> c = inv.getArgument(3);
            period.forEach(c);
            return null;
        }).when(scan).forEach(any(), any(), any(), any());
        return new WaybillReportService(scan, assembler, tenant);
    }

    @Test
    @DisplayName("бланк «bus»: только автобусы 1-АД, микроавтобус и 3-С не смешиваются")
    void billFiltersForms() {
        TenantScope tenant = mock(TenantScope.class);
        var period = List.of(wb(WaybillType.WB_BUS, "1"), wb(WaybillType.WB_MINIBUS, "1"), wb(WaybillType.WB_TAXI, "1"));
        var s = service(period, new WaybillCalcAssembler.View("PASSENGER", null, null, List.of()), tenant, new java.util.ArrayList<>());

        WaybillReport bus = s.passenger(ReportType.BY_VEHICLE, FROM, TO, null,
                WaybillReportService.Filter.of(null, null, EnumSet.of(WaybillType.WB_BUS)));
        assertThat(bus.totals().waybills()).isEqualTo(1);
        WaybillReport all = s.passenger(ReportType.BY_VEHICLE, FROM, TO, null);
        assertThat(all.totals().waybills()).isEqualTo(3);
    }

    @Test
    @DisplayName("грузовой: P и Z, рабочие дни и часы попадают в строку; фарқият = норма − выдано")
    void cargoColumnsAndDeviationSign() {
        TenantScope tenant = mock(TenantScope.class);
        var cargo = new CargoCalcResult(100, CoefficientBreakdown.neutral(), "1", false, 1250.5, 3,
                List.of(new FuelConsumption(2, 40, 0, 32, 10, 18)), 32, new DriverSalary(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        var view = new WaybillCalcAssembler.View("CARGO", null, cargo, List.of(), List.of(), 2, 600);
        var s = service(List.of(wb(WaybillType.WB_TRUCK, "1")), view, tenant, new java.util.ArrayList<>());

        WaybillReport r = s.cargo(ReportType.BY_VEHICLE, FROM, TO, null);

        var row = r.totals();
        assertThat(row.transportWork()).isEqualTo(1250.5);
        assertThat(row.trips()).isEqualTo(3d);
        assertThat(row.workDays()).isEqualTo(2);
        assertThat(row.workHours()).isEqualTo(10d);
        assertThat(row.fuelDeviationLiters()).isEqualTo(-8d);   // 32 − 40: перерасход — отрицательный
    }

    @Test
    @DisplayName("перевозчик с филиалами выбирает свою организацию; чужую — не может")
    void tenantPicksOwnOrganization() {
        TenantScope tenant = mock(TenantScope.class);
        when(tenant.isBounded()).thenReturn(true);
        when(tenant.rmas()).thenReturn(Set.of("100", "101"));
        var scopes = new java.util.ArrayList<Set<String>>();
        var s = service(List.of(), new WaybillCalcAssembler.View("PASSENGER", null, null, List.of()), tenant, scopes);

        s.passenger(ReportType.BY_VEHICLE, FROM, TO, "101");
        s.passenger(ReportType.BY_VEHICLE, FROM, TO, "999");

        assertThat(scopes.get(0)).containsExactly("101");
        assertThat(scopes.get(1)).containsExactlyInAnyOrder("100", "101");
    }
}
