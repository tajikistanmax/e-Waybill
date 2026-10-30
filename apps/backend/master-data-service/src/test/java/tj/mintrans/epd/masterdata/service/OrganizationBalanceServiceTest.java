package tj.mintrans.epd.masterdata.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.masterdata.domain.OrganizationBalance;
import tj.mintrans.epd.masterdata.repository.BillingLedgerRepository;
import tj.mintrans.epd.masterdata.repository.OrganizationBalanceRepository;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Решение владельца 31.10.2026: баланс биллинга выдачи ПЛ — пост-оплата (уходит в минус, не
 * блокирует), списание идемпотентно по waybillId.
 */
class OrganizationBalanceServiceTest {

    private OrganizationBalanceRepository balances;
    private BillingLedgerRepository ledger;
    private OrganizationBalanceService service;

    @BeforeEach
    void setUp() {
        balances = mock(OrganizationBalanceRepository.class);
        ledger = mock(BillingLedgerRepository.class);
        service = new OrganizationBalanceService(balances, ledger);
    }

    @Test
    void topUpCreatesBalanceIfMissingAndAddsAmount() {
        var orgId = UUID.randomUUID();
        when(balances.findByIdForUpdate(orgId)).thenReturn(Optional.empty());
        when(balances.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(ledger.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var entry = service.topUp(orgId, new BigDecimal("100.00"), "REF-1", "пополнение", "admin");

        assertThat(entry.getDirection()).isEqualTo("CREDIT");
        assertThat(entry.getAmountTjs()).isEqualByComparingTo("100.00");
    }

    @Test
    void debitGoesNegativeWithoutBlocking() {
        var orgId = UUID.randomUUID();
        var existing = new OrganizationBalance();
        existing.setOrganizationId(orgId);
        existing.setBalanceTjs(new BigDecimal("10.00"));
        when(balances.findByIdForUpdate(orgId)).thenReturn(Optional.of(existing));
        when(ledger.existsByWaybillId(any())).thenReturn(false);

        var waybillId = UUID.randomUUID();
        service.debitForWaybill(orgId, waybillId, "WB_CAR", new BigDecimal("40.00"), "waybill-service");

        assertThat(existing.getBalanceTjs()).isEqualByComparingTo("-30.00");
        verify(balances).save(existing);
        verify(ledger).save(any());
    }

    @Test
    void debitIsIdempotentPerWaybill() {
        var orgId = UUID.randomUUID();
        var waybillId = UUID.randomUUID();
        when(ledger.existsByWaybillId(waybillId)).thenReturn(true);

        service.debitForWaybill(orgId, waybillId, "WB_CAR", new BigDecimal("40.00"), "waybill-service");

        verify(balances, never()).findByIdForUpdate(any());
        verify(ledger, never()).save(any());
    }

    @Test
    void zeroOrNegativeAmount_doesNothing() {
        var orgId = UUID.randomUUID();
        service.debitForWaybill(orgId, UUID.randomUUID(), "WB_CAR", BigDecimal.ZERO, "waybill-service");
        verify(balances, never()).findByIdForUpdate(any());
    }
}
