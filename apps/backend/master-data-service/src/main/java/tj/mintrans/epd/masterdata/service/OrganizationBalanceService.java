package tj.mintrans.epd.masterdata.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.masterdata.domain.BillingLedgerEntry;
import tj.mintrans.epd.masterdata.domain.OrganizationBalance;
import tj.mintrans.epd.masterdata.repository.BillingLedgerRepository;
import tj.mintrans.epd.masterdata.repository.OrganizationBalanceRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Баланс организации по биллингу выдачи ПЛ (решение владельца 31.10.2026, пост-оплата —
 * баланс может уйти в минус, списание не блокирует выдачу). Строка баланса блокируется
 * {@code SELECT … FOR UPDATE} (как {@code Waybill.findByIdForUpdate}) — конкурентные
 * списание/пополнение одной организации сериализуются, а не теряют друг друга.
 */
@Service
public class OrganizationBalanceService {

    private final OrganizationBalanceRepository balances;
    private final BillingLedgerRepository ledger;

    public OrganizationBalanceService(OrganizationBalanceRepository balances, BillingLedgerRepository ledger) {
        this.balances = balances;
        this.ledger = ledger;
    }

    public BigDecimal balance(UUID organizationId) {
        return balances.findById(organizationId).map(OrganizationBalance::getBalanceTjs).orElse(BigDecimal.ZERO);
    }

    public Page<BillingLedgerEntry> ledger(UUID organizationId, Pageable pageable) {
        return ledger.findByOrganizationIdOrderByCreatedAtDesc(organizationId, pageable);
    }

    /**
     * Пополнение баланса (SYSTEM_ADMIN, по факту внешнего банковского перевода). {@code amount}
     * должна быть положительной — проверяется в контроллере вместе с правами.
     */
    @Transactional
    public BillingLedgerEntry topUp(UUID organizationId, BigDecimal amount, String reference, String note, String actor) {
        var bal = balances.findByIdForUpdate(organizationId).orElseGet(() -> {
            var b = new OrganizationBalance();
            b.setOrganizationId(organizationId);
            b.setBalanceTjs(BigDecimal.ZERO);
            return b;
        });
        bal.setBalanceTjs(bal.getBalanceTjs().add(amount));
        bal.setUpdatedAt(OffsetDateTime.now());
        balances.save(bal);
        var entry = new BillingLedgerEntry();
        entry.setOrganizationId(organizationId);
        entry.setDirection(BillingLedgerEntry.CREDIT);
        entry.setAmountTjs(amount);
        entry.setReference(reference);
        entry.setNote(note);
        entry.setCreatedBy(actor);
        return ledger.save(entry);
    }

    /**
     * Списание за выдачу ПЛ (Т1, вызывается waybill-service по сервисному токену).
     * Идемпотентно по {@code waybillId} (уникальный индекс uq_ledger_waybill_debit, миграция
     * V92): повтор того же {@code waybillId} при ретрае сетевого сбоя не списывает дважды —
     * возвращает уже существующую строку без изменения баланса. Баланс МОЖЕТ уйти в минус
     * (пост-оплата, решение владельца) — списание никогда не бросает исключение по недостатку
     * средств, иначе выдача ПЛ стала бы заблокированной оплатой, чего владелец явно не хотел.
     */
    @Transactional
    public void debitForWaybill(UUID organizationId, UUID waybillId, String waybillType, BigDecimal amount, String actor) {
        if (amount.signum() <= 0 || ledger.existsByWaybillId(waybillId)) {
            return;
        }
        var bal = balances.findByIdForUpdate(organizationId).orElseGet(() -> {
            var b = new OrganizationBalance();
            b.setOrganizationId(organizationId);
            b.setBalanceTjs(BigDecimal.ZERO);
            return b;
        });
        bal.setBalanceTjs(bal.getBalanceTjs().subtract(amount));
        bal.setUpdatedAt(OffsetDateTime.now());
        balances.save(bal);
        var entry = new BillingLedgerEntry();
        entry.setOrganizationId(organizationId);
        entry.setDirection(BillingLedgerEntry.DEBIT);
        entry.setAmountTjs(amount);
        entry.setWaybillId(waybillId);
        entry.setWaybillType(waybillType);
        entry.setCreatedBy(actor);
        ledger.save(entry);
    }
}
