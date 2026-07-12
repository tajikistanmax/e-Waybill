package tj.mintrans.epd.waybill.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.config.CurrentUser;
import tj.mintrans.epd.waybill.domain.Expense;
import tj.mintrans.epd.waybill.repository.ExpenseRepository;
import tj.mintrans.epd.waybill.web.error.ApiErrors.ConflictException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Расходы рейса (§12): добавление/список/подтверждение бухгалтером/удаление.
 * Изменение запрещено после закрытия ПЛ; подтверждённый расход неизменяем (антифрод).
 */
@Service
public class ExpenseService {

    private static final Set<String> TYPES =
            Set.of("PER_DIEM", "TOLL", "PARKING", "LODGING", "REPAIR", "OTHER");

    private final ExpenseRepository expenses;
    private final WaybillService waybillService;
    private final CurrentUser currentUser;

    public ExpenseService(ExpenseRepository expenses, WaybillService waybillService, CurrentUser currentUser) {
        this.expenses = expenses;
        this.waybillService = waybillService;
        this.currentUser = currentUser;
    }

    @Transactional
    public Expense add(UUID waybillId, String type, BigDecimal amount, String currency, BigDecimal rate,
                       BigDecimal vat, String description, String receiptNumber, LocalDate spentAt) {
        var wb = waybillService.get(waybillId); // 404 + тенант-проверка
        if (wb.getStatus().isTerminal()) {
            throw new ConflictException("Расходы нельзя добавлять после закрытия путевого листа (" + wb.getStatus() + ")");
        }
        if (type == null || !TYPES.contains(type)) {
            throw new UnprocessableException("Недопустимый тип расхода. Допустимо: " + TYPES);
        }
        if (amount == null || amount.signum() < 0) {
            throw new UnprocessableException("Сумма расхода не может быть отрицательной");
        }
        if (vat != null && vat.signum() < 0) {
            throw new UnprocessableException("Сумма НДС не может быть отрицательной");
        }
        var e = new Expense();
        e.setWaybillId(waybillId);
        e.setExpenseType(type);
        e.setAmount(amount);
        if (currency != null && !currency.isBlank()) e.setCurrency(currency.trim().toUpperCase());
        e.setRate(rate);
        e.setVat(vat);
        e.setDescription(description);
        e.setReceiptNumber(receiptNumber);
        e.setSpentAt(spentAt);
        e.setCreatedBy(currentUser.username().orElse(null));
        return expenses.save(e);
    }

    public List<Expense> list(UUID waybillId) {
        waybillService.get(waybillId); // 404 + тенант-проверка
        return expenses.findByWaybillIdOrderByCreatedAt(waybillId);
    }

    /** Подтверждение расхода бухгалтером — фиксирует запись (дальше неизменяема/неудаляема). */
    @Transactional
    public Expense confirm(UUID expenseId) {
        var e = expenses.findById(expenseId).orElseThrow(() -> new NotFoundException("Расход не найден"));
        waybillService.get(e.getWaybillId()); // тенант-проверка по ПЛ
        if (e.isConfirmed()) {
            return e;
        }
        e.setConfirmed(true);
        e.setConfirmedBy(currentUser.username().orElse(null));
        e.setConfirmedAt(OffsetDateTime.now());
        return expenses.save(e);
    }

    @Transactional
    public void delete(UUID expenseId) {
        var e = expenses.findById(expenseId).orElseThrow(() -> new NotFoundException("Расход не найден"));
        var wb = waybillService.get(e.getWaybillId()); // тенант-проверка
        if (e.isConfirmed()) {
            throw new ConflictException("Подтверждённый бухгалтером расход удалить нельзя");
        }
        if (wb.getStatus().isTerminal()) {
            throw new ConflictException("Расход нельзя удалить после закрытия путевого листа");
        }
        expenses.delete(e);
    }
}
