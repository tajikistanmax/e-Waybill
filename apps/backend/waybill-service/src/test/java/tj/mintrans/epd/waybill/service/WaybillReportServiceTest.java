package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.calc.WaybillCalcAssembler;
import tj.mintrans.epd.waybill.calc.report.ReportRow;
import tj.mintrans.epd.waybill.calc.report.ReportType;
import tj.mintrans.epd.waybill.calc.report.WaybillReport;
import tj.mintrans.epd.waybill.config.TenantScope;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.domain.WaybillType;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * MIGRATION.md §6.7 — отбор строк типового отчёта по одному ТС / водителю
 * (legacy {@code report_details}: тип 10 по {@code parking_id}). Расчёт по ПЛ заглушен
 * (нейтральный {@link WaybillCalcAssembler.View}) — проверяется только отбор и группировка.
 */
class WaybillReportServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    private WaybillReportService service;

    @BeforeEach
    void setUp() {
        WaybillPeriodScan scan = mock(WaybillPeriodScan.class);
        WaybillCalcAssembler assembler = mock(WaybillCalcAssembler.class);
        TenantScope tenant = mock(TenantScope.class);
        lenient().when(tenant.isBounded()).thenReturn(false);
        lenient().when(assembler.calculate(any(), any()))
                .thenReturn(new WaybillCalcAssembler.View("PASSENGER", null, null, List.of()));
        lenient().when(assembler.calculate(any(), any(), any()))
                .thenReturn(new WaybillCalcAssembler.View("PASSENGER", null, null, List.of()));

        List<Waybill> period = List.of(
                wb(WaybillType.WB_BUS, "0114TJ01", "461930031", 100),
                wb(WaybillType.WB_BUS, "0114 tj 01", "461930031", 50),   // тот же ТС, другое написание
                wb(WaybillType.WB_BUS, "2222TJ01", "461930032", 300),
                wb(WaybillType.WB_TRUCK, "0114TJ01", "461930031", 700), // грузовой — не попадает в пассажирский
                // Не должны попасть ни в один отчёт: аннулированный после выдачи номера, черновик,
                // недопущенный врачом (номера нет).
                status(wb(WaybillType.WB_BUS, "3333TJ01", "461930033", 900), WaybillStatus.CANCELLED, "01-26-04-0000099-1"),
                status(wb(WaybillType.WB_BUS, "3333TJ01", "461930033", 900), WaybillStatus.DRAFT, null),
                status(wb(WaybillType.WB_TRUCK, "3333TJ01", "461930033", 900), WaybillStatus.MED_REJECTED, null));
        doAnswer(inv -> {
            Consumer<Waybill> c = inv.getArgument(3);
            period.forEach(c);
            return null;
        }).when(scan).forEach(any(), any(), any(), any());

        service = new WaybillReportService(scan, assembler, tenant);
    }

    private static int seq = 0;

    private static Waybill wb(WaybillType type, String plate, String driver, int distance) {
        Waybill w = new Waybill();
        w.setWaybillType(type);
        w.setVehicleRegNumber(plate);
        w.setDriverRma(driver);
        w.setOdometerExit(1000);
        w.setOdometerEntry(1000 + distance);
        w.setStatus(WaybillStatus.COMPLETED);
        w.setNumber("01-26-04-%07d-0".formatted(++seq));
        return w;
    }

    private static Waybill status(Waybill w, WaybillStatus s, String number) {
        w.setStatus(s);
        w.setNumber(number);
        return w;
    }

    @Test
    @DisplayName("аннулированные, черновики и недопущенные листы в отчёт не попадают (находка 23.09)")
    void notWorkedWaybillsAreExcluded() {
        WaybillReport pax = service.passenger(ReportType.REGISTRY_JOURNAL, FROM, TO, null);
        assertThat(pax.rows()).hasSize(3);
        assertThat(pax.totals().distanceKm()).isEqualTo(450d);
        assertThat(pax.rows()).extracting(ReportRow::key).allMatch(k -> k.startsWith("01-26-"));

        WaybillReport cargo = service.cargo(ReportType.BY_VEHICLE, FROM, TO, null);
        assertThat(cargo.totals().distanceKm()).isEqualTo(700d);
    }

    @Test
    @DisplayName("без отбора — прежнее поведение: все пассажирские ПЛ периода (2 ТС, 450 км)")
    void noFilter() {
        WaybillReport r = service.passenger(ReportType.BY_VEHICLE, FROM, TO, null);

        assertThat(r.rows()).extracting(ReportRow::key).containsExactly("0114 tj 01", "0114TJ01", "2222TJ01");
        assertThat(r.totals().distanceKm()).isEqualTo(450d);
    }

    @Test
    @DisplayName("отбор по ТС: госномер сравнивается без регистра и пробелов (legacy parking_id) — 150 км")
    void byVehicle() {
        WaybillReport r = service.passenger(ReportType.BY_VEHICLE, FROM, TO, null,
                WaybillReportService.Filter.of(" 0114tj01 ", null));

        assertThat(r.rows()).hasSize(2);
        assertThat(r.totals().distanceKm()).isEqualTo(150d);
    }

    @Test
    @DisplayName("отбор по водителю и по ТС одновременно — пересечение")
    void byDriverAndVehicle() {
        WaybillReport driverOnly = service.passenger(ReportType.BY_DRIVER, FROM, TO, null,
                WaybillReportService.Filter.of(null, "461930032"));
        assertThat(driverOnly.rows()).extracting(ReportRow::key).containsExactly("461930032");
        assertThat(driverOnly.totals().distanceKm()).isEqualTo(300d);

        WaybillReport none = service.passenger(ReportType.BY_DRIVER, FROM, TO, null,
                WaybillReportService.Filter.of("2222TJ01", "461930031"));
        assertThat(none.rows()).isEmpty();
        assertThat(none.totals().distanceKm()).isZero();
    }

    @Test
    @DisplayName("пустые/пробельные значения отбора = без отбора (Filter.NONE)")
    void blankFilterIsNone() {
        assertThat(WaybillReportService.Filter.of("  ", "")).isSameAs(WaybillReportService.Filter.NONE);
        assertThat(service.cargo(ReportType.BY_VEHICLE, FROM, TO, null, WaybillReportService.Filter.of("", null))
                .totals().distanceKm()).isEqualTo(700d);
    }
}
