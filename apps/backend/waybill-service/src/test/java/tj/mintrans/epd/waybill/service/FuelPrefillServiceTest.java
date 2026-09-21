package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import tj.mintrans.epd.waybill.domain.FuelRecord;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.repository.FuelRecordRepository;
import tj.mintrans.epd.waybill.repository.WaybillRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIGRATION.md §4.9 — автоподстановка остатка топлива и нормы к выдаче из предыдущего ПЛ того же ТС
 * (legacy {@code parking_fuel_left}: remain_fuel_entry предыдущего; {@code parking_fuel_give}: be_given).
 */
class FuelPrefillServiceTest {

    private FuelRecordRepository fuelRecords;
    private WaybillRepository waybills;
    private WaybillService waybillService;
    private FuelPrefillService service;

    private final UUID current = UUID.randomUUID();
    private final UUID previous = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        fuelRecords = mock(FuelRecordRepository.class);
        waybills = mock(WaybillRepository.class);
        waybillService = mock(WaybillService.class);
        service = new FuelPrefillService(waybillService, fuelRecords, waybills);
    }

    private static FuelRecord rec(UUID waybillId, short fuelType, String remainEntry, String beGiven) {
        FuelRecord f = new FuelRecord();
        f.setWaybillId(waybillId);
        f.setFuelType(fuelType);
        f.setRemainEntry(remainEntry == null ? null : new BigDecimal(remainEntry));
        f.setBeGiven(beGiven == null ? null : new BigDecimal(beGiven));
        return f;
    }

    @Test
    @DisplayName("остаток до выезда = remain_entry предыдущего ПЛ того же ТС по виду топлива; текущий ПЛ пропускается")
    void takesPreviousWaybillSkippingCurrent() {
        // Новые сверху: первая строка — текущего ПЛ (её пропускаем), вторая — предыдущего.
        when(fuelRecords.findLatestForVehicle(eq("2200TJ02"), eq((short) 2), any(Pageable.class)))
                .thenReturn(List.of(rec(current, (short) 2, "5", "70"), rec(previous, (short) 2, "17.46", "85")));
        Waybill prevWb = new Waybill();
        prevWb.setNumber("04-26-03-0000001-1");
        when(waybills.findById(previous)).thenReturn(Optional.of(prevWb));

        var p = service.forVehicle("2200TJ02", (short) 2, current);

        assertThat(p.found()).isTrue();
        assertThat(p.remainBeforeExit()).isEqualByComparingTo("17.46");   // parking_fuel_left
        assertThat(p.beGiven()).isEqualByComparingTo("85");               // parking_fuel_give
        assertThat(p.sourceWaybillId()).isEqualTo(previous);
        assertThat(p.sourceWaybillNumber()).isEqualTo("04-26-03-0000001-1");
    }

    @Test
    @DisplayName("нет предыдущих записей по этому виду топлива → found=false, значения null (legacy remain=0)")
    void nothingFound() {
        when(fuelRecords.findLatestForVehicle(any(), anyShort(), any(Pageable.class))).thenReturn(List.of());

        var p = service.forVehicle("0000TJ00", (short) 1, current);

        assertThat(p.found()).isFalse();
        assertThat(p.remainBeforeExit()).isNull();
        assertThat(p.beGiven()).isNull();
    }

    @Test
    @DisplayName("forWaybill берёт госномер из ПЛ и исключает его самого")
    void forWaybillUsesVehicleOfWaybill() {
        Waybill wb = new Waybill();
        wb.setVehicleRegNumber("2200TJ02");
        when(waybillService.get(current)).thenReturn(wb);
        when(fuelRecords.findLatestForVehicle(eq("2200TJ02"), eq((short) 2), any(Pageable.class)))
                .thenReturn(List.of(rec(previous, (short) 2, "12", null)));
        when(waybills.findById(previous)).thenReturn(Optional.empty());

        var p = service.forWaybill(current, (short) 2);

        assertThat(p.found()).isTrue();
        assertThat(p.remainBeforeExit()).isEqualByComparingTo("12");
        assertThat(p.beGiven()).isNull();
        assertThat(p.sourceWaybillNumber()).isNull();
    }
}
