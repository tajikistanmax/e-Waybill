package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tj.mintrans.epd.waybill.calc.WaybillCalcAssembler;
import tj.mintrans.epd.waybill.calc.model.CargoCalcResult;
import tj.mintrans.epd.waybill.calc.model.PassengerCalcResult;
import tj.mintrans.epd.waybill.calc.model.PassengerMetrics;
import tj.mintrans.epd.waybill.client.MasterDataClient;
import tj.mintrans.epd.waybill.config.TenantScope;
import tj.mintrans.epd.waybill.domain.ConsignmentNote;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.domain.WorkDay;
import tj.mintrans.epd.waybill.repository.ConsignmentNoteRepository;
import tj.mintrans.epd.waybill.repository.WorkDayRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Статформа «1-авто» (сверка 25.09, D4): строки 01–44 по всем видам ПЛ организации. */
class Stat1AutoServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    private static Waybill wb(WaybillType type, String reg) {
        Waybill w = new Waybill();
        ReflectionTestUtils.setField(w, "id", UUID.randomUUID());
        w.setWaybillType(type);
        w.setVehicleRegNumber(reg);
        w.setStatus(WaybillStatus.COMPLETED);
        w.setNumber("N-" + reg);
        ReflectionTestUtils.setField(w, "createdAt", OffsetDateTime.of(2026, 9, 10, 6, 0, 0, 0, ZoneOffset.UTC));
        return w;
    }

    private static WaybillCalcAssembler.View passenger(double pass, double pkm, double totalKm, double routeKm, int minutes) {
        PassengerMetrics m = new PassengerMetrics(1, 10, 0, pkm, pass, routeKm, totalKm, minutes,
                BigDecimal.ZERO, BigDecimal.ZERO, false);
        PassengerCalcResult r = new PassengerCalcResult(0, minutes, minutes / 60d, null, List.of(), 0, null, m, null);
        return new WaybillCalcAssembler.View("PASSENGER", r, null, List.of(), List.of(), 1, minutes);
    }

    @Test
    @DisplayName("автобус, такси 3-С на 2 дня, грузовой с борхатом → строки 01–44; парк ТС × дней периода")
    void fullForm() {
        WaybillPeriodScan scan = mock(WaybillPeriodScan.class);
        WaybillCalcAssembler assembler = mock(WaybillCalcAssembler.class);
        TenantScope tenant = mock(TenantScope.class);
        when(tenant.isBounded()).thenReturn(false);
        MasterDataClient md = mock(MasterDataClient.class);
        lenient().when(md.countVehiclesByOrganization(anyInt())).thenReturn(Map.of());
        when(md.countVehiclesByOrganization(1)).thenReturn(Map.of("ORG", 3L, "OTHER", 9L));
        when(md.countVehiclesByOrganization(3)).thenReturn(Map.of("ORG", 2L));
        when(md.countVehiclesByOrganization(4)).thenReturn(Map.of("ORG", 1L));
        when(md.countVehiclesByOrganization(5)).thenReturn(Map.of("ORG", 1L));
        WorkDayRepository workDays = mock(WorkDayRepository.class);
        ConsignmentNoteRepository notes = mock(ConsignmentNoteRepository.class);

        Waybill bus = wb(WaybillType.WB_BUS, "0101TJ01");
        Waybill taxi = wb(WaybillType.WB_TAXI, "0202TJ01");
        Waybill truck = wb(WaybillType.WB_TRUCK, "0303TJ01");
        when(assembler.calculate(eq(bus), any(), any())).thenReturn(passenger(1000, 20000, 300, 250, 600));
        when(assembler.calculate(eq(taxi), any(), any())).thenReturn(passenger(40, 600, 200, 150, 1200));
        CargoCalcResult cargo = new CargoCalcResult(120, null, null, false, 5000, 2, List.of(), 0, null);
        when(assembler.calculate(eq(truck), any(), any()))
                .thenReturn(new WaybillCalcAssembler.View("CARGO", null, cargo, List.of(), List.of(), 1, 480));

        WorkDay d1 = mock(WorkDay.class);
        when(d1.getWorkDate()).thenReturn(LocalDate.of(2026, 9, 10));
        WorkDay d2 = mock(WorkDay.class);
        when(d2.getWorkDate()).thenReturn(LocalDate.of(2026, 9, 11));
        when(workDays.findByWaybillIdOrderByWorkDate(taxi.getId())).thenReturn(List.of(d1, d2));
        ConsignmentNote n = new ConsignmentNote();
        n.setKind((short) 1);
        n.setCargoWeight(new BigDecimal("10"));
        n.setDistance(new BigDecimal("25"));
        n.setTrips(2);
        when(notes.findByWaybillIdOrderByNoteDateAscNumberAsc(truck.getId())).thenReturn(List.of(n));

        doAnswer(inv -> {
            Consumer<Waybill> c = inv.getArgument(3);
            List.of(bus, taxi, truck).forEach(c);
            return null;
        }).when(scan).forEach(any(), any(), any(), any());

        Stat1AutoService service = new Stat1AutoService(scan, assembler, tenant, md, workDays, notes);
        Stat1AutoService.Report rep = service.build(FROM, TO, "ORG");

        Map<String, Double> byCode = rep.rows().stream()
                .filter(r -> r.code() != null && !r.code().isEmpty())
                .collect(Collectors.toMap(Stat1AutoService.Row::code, Stat1AutoService.Row::month));
        assertThat(byCode.get("01")).isEqualTo(0.02);      // 10 т × 2 рейса = 20 т
        assertThat(byCode.get("02")).isEqualTo(5.0);       // 5000 ткм
        assertThat(byCode.get("03")).isEqualTo(1.0);       // 1000 пасс.
        assertThat(byCode.get("04")).isEqualTo(20.0);
        assertThat(byCode.get("07")).isEqualTo(210.0);     // (3+2 автобусов + 1 такси + 1 грузовой) × 30 дней
        assertThat(byCode.get("09")).isEqualTo(150.0);
        assertThat(byCode.get("12")).isEqualTo(4.0);       // автобус 1 + такси 2 дня + грузовой 1
        assertThat(byCode.get("15")).isEqualTo(2.0);
        assertThat(byCode.get("17")).isEqualTo(38.0);      // (600 + 1200 + 480) мин
        assertThat(byCode.get("22")).isEqualTo(0.62);      // 300 + 200 + 120 км
        assertThat(byCode.get("28")).isEqualTo(0.05);      // 25 км × 2 рейса
        assertThat(byCode.get("40")).isEqualTo(3.0);
        assertThat(byCode).containsKeys("08", "10", "11", "13", "14", "16", "18", "21", "23", "26", "27", "31",
                "41", "42", "43", "44");
        // Сентябрь не с 1 января — «с начала года» считается отдельным проходом (здесь те же данные).
        assertThat(rep.rows().stream().filter(r -> "03".equals(r.code())).findFirst().orElseThrow().ytd()).isEqualTo(1.0);
    }
}
