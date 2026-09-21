package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.domain.FuelRecord;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.repository.FuelRecordRepository;
import tj.mintrans.epd.waybill.repository.WorkDayRepository;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIGRATION.md §12.4 (лимит видов топлива по форме: 2-Б/5Б-БМ — 1, пассажирские — 2), §12.8 (довыдача
 * «Харҷи иловагӣ» для 1-АД 0…5 л) и §12.5 (суточный пробег 650 км для 1-А/3-С, по флагу).
 */
class WorkDayServiceLimitsTest {

    private final WorkDayRepository workDays = mock(WorkDayRepository.class);
    private final FuelRecordRepository fuelRecords = mock(FuelRecordRepository.class);
    private final WaybillService waybillService = mock(WaybillService.class);

    private WorkDayService service(boolean dailyKmEnabled) {
        lenient().when(fuelRecords.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(workDays.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return new WorkDayService(workDays, fuelRecords, waybillService, dailyKmEnabled);
    }

    private static Waybill waybill(WaybillType type, WaybillStatus status) {
        Waybill wb = new Waybill();
        wb.setWaybillType(type);
        wb.setStatus(status);
        wb.setValidFrom(OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC));
        wb.setValidTo(OffsetDateTime.of(2026, 9, 30, 0, 0, 0, 0, ZoneOffset.UTC));
        wb.setVehicleSnapshot(Map.of("fuelType", 2));
        return wb;
    }

    private static FuelRecord fuel(short type) {
        FuelRecord r = new FuelRecord();
        r.setFuelType(type);
        r.setFuelGiven(new BigDecimal("50"));
        return r;
    }

    @Test
    @DisplayName("12.4: у 2-Б уже есть дизель — второй вид (газ) отклоняется; тот же вид — допускается")
    void cargoFormAllowsSingleFuelType() {
        UUID id = UUID.randomUUID();
        when(waybillService.get(id)).thenReturn(waybill(WaybillType.WB_TRUCK, WaybillStatus.ACTIVE));
        when(fuelRecords.findByWaybillIdOrderByCreatedAt(id)).thenReturn(List.of(fuel((short) 2)));
        WorkDayService s = service(false);

        assertThatThrownBy(() -> s.addFuel(id, null, (short) 3, new BigDecimal("10"), null, null))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("2-Б").hasMessageContaining("не более 1");
        assertThatCode(() -> s.addFuel(id, null, (short) 2, new BigDecimal("10"), null, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("12.4: у пассажирского 1-А допускаются два вида (дизель + газ), третий — нет")
    void passengerFormAllowsTwoFuelTypes() {
        UUID id = UUID.randomUUID();
        when(waybillService.get(id)).thenReturn(waybill(WaybillType.WB_MINIBUS, WaybillStatus.ACTIVE));
        when(fuelRecords.findByWaybillIdOrderByCreatedAt(id)).thenReturn(List.of(fuel((short) 2)));
        WorkDayService s = service(false);

        assertThatCode(() -> s.addFuel(id, null, (short) 3, new BigDecimal("10"), null, null)).doesNotThrowAnyException();

        when(fuelRecords.findByWaybillIdOrderByCreatedAt(id)).thenReturn(List.of(fuel((short) 2), fuel((short) 3)));
        assertThatThrownBy(() -> s.addFuel(id, null, (short) 4, new BigDecimal("10"), null, null))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("не более 2");
    }

    @Test
    @DisplayName("12.8: 1-АД — довыдача 6 л отклоняется, 5 л принимается; у 2-Б лимита нет (20 л — ок)")
    void additionalFuelLimitForBus() {
        UUID bus = UUID.randomUUID();
        when(waybillService.get(bus)).thenReturn(waybill(WaybillType.WB_BUS, WaybillStatus.ACTIVE));
        when(fuelRecords.findByWaybillIdOrderByCreatedAt(bus)).thenReturn(List.of());
        WorkDayService s = service(false);

        assertThatThrownBy(() -> s.addFuel(bus, null, (short) 2, new BigDecimal("80"), null, null, new BigDecimal("6"), null))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("не более 5 л");
        assertThatCode(() -> s.addFuel(bus, null, (short) 2, new BigDecimal("80"), null, null, new BigDecimal("5"), null))
                .doesNotThrowAnyException();

        UUID truck = UUID.randomUUID();
        when(waybillService.get(truck)).thenReturn(waybill(WaybillType.WB_TRUCK, WaybillStatus.ACTIVE));
        when(fuelRecords.findByWaybillIdOrderByCreatedAt(truck)).thenReturn(List.of());
        assertThatCode(() -> s.addFuel(truck, null, (short) 2, new BigDecimal("80"), null, null, new BigDecimal("20"), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("12.5: рабочий день 1-А на 700 км — 422 при включённом лимите, принимается при выключенном (по умолчанию)")
    void dailyKmLimitByFlag() {
        UUID id = UUID.randomUUID();
        when(waybillService.get(id)).thenReturn(waybill(WaybillType.WB_MINIBUS, WaybillStatus.ACTIVE));
        when(workDays.existsByWaybillIdAndWorkDate(any(), any())).thenReturn(false);
        when(workDays.countByWaybillId(id)).thenReturn(0L);
        LocalDate date = LocalDate.of(2026, 9, 10);

        WorkDayService enabled = service(true);
        assertThatThrownBy(() -> enabled.addWorkDay(id, date, LocalTime.of(8, 0), LocalTime.of(20, 0), 1000, 1700, 4, null))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("700 км").hasMessageContaining("650 км");
        assertThatCode(() -> enabled.addWorkDay(id, date, LocalTime.of(8, 0), LocalTime.of(20, 0), 1000, 1650, 4, null))
                .doesNotThrowAnyException();

        WorkDayService disabled = service(false);
        assertThatCode(() -> disabled.addWorkDay(id, date, LocalTime.of(8, 0), LocalTime.of(20, 0), 1000, 1700, 4, null))
                .doesNotThrowAnyException();
    }
}
