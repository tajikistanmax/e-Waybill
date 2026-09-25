package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.calc.WaybillCalcAssembler;
import tj.mintrans.epd.waybill.calc.model.FuelConsumption;
import tj.mintrans.epd.waybill.calc.model.PassengerCalcResult;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * «Бақияи пас аз даромад» (сверка 25.09.2026): остаток после возврата считается расчётом листа и
 * записывается в последнюю строку своего вида топлива — её читает автоподстановка следующему листу.
 */
class FuelBalanceServiceTest {

    private final WaybillRepository waybills = mock(WaybillRepository.class);
    private final FuelRecordRepository fuel = mock(FuelRecordRepository.class);
    private final WaybillCalcAssembler assembler = mock(WaybillCalcAssembler.class);
    private final FuelBalanceService service = new FuelBalanceService(waybills, fuel, assembler);

    private static FuelRecord line(short type, String remainEntry) {
        FuelRecord r = new FuelRecord();
        r.setFuelType(type);
        r.setFuelGiven(new BigDecimal("40"));
        if (remainEntry != null) r.setRemainEntry(new BigDecimal(remainEntry));
        return r;
    }

    private static WaybillCalcAssembler.View view(FuelConsumption... fuels) {
        var p = new PassengerCalcResult(100, 0, 0d, null, List.of(fuels), 0d, null, null, null);
        return new WaybillCalcAssembler.View("PASSENGER", p, null, List.of());
    }

    @Test
    @DisplayName("две строки дизеля: остаток 24.18 — в последней, в первой очищается (сумма не удваивается)")
    void remainGoesToLastLineOfType() {
        UUID id = UUID.randomUUID();
        Waybill wb = new Waybill();
        wb.setOdometerEntry(1100);
        when(waybills.findById(id)).thenReturn(Optional.of(wb));
        FuelRecord first = line((short) 2, "5");   // ручной ввод раньше — будет очищен
        FuelRecord last = line((short) 2, null);
        when(fuel.findByWaybillIdOrderByCreatedAt(id)).thenReturn(List.of(first, last));
        when(assembler.calculate(any(), any())).thenReturn(view(new FuelConsumption(2, 80, 3, 68.82, 10, 24.18)));

        service.recompute(id);

        assertThat(last.getRemainEntry()).isEqualByComparingTo("24.180");
        assertThat(first.getRemainEntry()).isNull();
    }

    @Test
    @DisplayName("до возврата (нет одометра возврата) — ничего не считается")
    void noReturnNoRecompute() {
        UUID id = UUID.randomUUID();
        when(waybills.findById(id)).thenReturn(Optional.of(new Waybill()));

        service.recompute(id);

        verify(assembler, never()).calculate(any(), any());
    }
}
