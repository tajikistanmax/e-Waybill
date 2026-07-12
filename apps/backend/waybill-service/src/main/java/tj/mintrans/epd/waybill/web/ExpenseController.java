package tj.mintrans.epd.waybill.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.waybill.domain.Expense;
import tj.mintrans.epd.waybill.service.ExpenseService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Расходы рейса (§12): добавление/список по ПЛ, подтверждение бухгалтером, удаление.
 * Тенант-проверка и статус-проверки — в ExpenseService (через WaybillService.get()).
 */
@RestController
@RequestMapping("/api/v1")
public class ExpenseController {

    private final ExpenseService service;

    public ExpenseController(ExpenseService service) {
        this.service = service;
    }

    public record ExpenseRequest(
            @NotBlank String expenseType,
            @NotNull BigDecimal amount,
            String currency,
            BigDecimal rate,
            BigDecimal vat,
            String description,
            String receiptNumber,
            LocalDate spentAt) {
    }

    @PostMapping("/waybills/{id}/expenses")
    @PreAuthorize("hasAnyRole('DISPATCHER','DRIVER','SYSTEM_ADMIN')")
    public ResponseEntity<Expense> add(@PathVariable UUID id, @Valid @RequestBody ExpenseRequest req) {
        var e = service.add(id, req.expenseType(), req.amount(), req.currency(), req.rate(),
                req.vat(), req.description(), req.receiptNumber(), req.spentAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(e);
    }

    @GetMapping("/waybills/{id}/expenses")
    public List<Expense> list(@PathVariable UUID id) {
        return service.list(id);
    }

    /** Подтверждение расхода бухгалтером (§12). */
    @PostMapping("/expenses/{eid}/confirm")
    @PreAuthorize("hasAnyRole('ACCOUNTANT','SYSTEM_ADMIN')")
    public Expense confirm(@PathVariable UUID eid) {
        return service.confirm(eid);
    }

    @DeleteMapping("/expenses/{eid}")
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID eid) {
        service.delete(eid);
        return ResponseEntity.noContent().build();
    }
}
