package tj.mintrans.epd.waybill.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.waybill.domain.VerifyScanLog;
import tj.mintrans.epd.waybill.repository.VerifyScanLogRepository;

import java.util.List;

/**
 * История публичных проверок ПЛ по QR (§18 QA) — для НАДЗОРА, не аноним.
 *
 * <p>Читает журнал, который анонимный {@code VerifyController} наполняет при каждом
 * сканировании. Роли повторяют набор надзорных read-эндпоинтов (ср. {@code NeruController}
 * по госномеру, инспекторские выборки в {@code WaybillController}): INSPECTOR (дорожный
 * контроль), MINTRANS_ANALYST (аналитика надзора), SYSTEM_ADMIN. Отдельный путь
 * {@code /api/v1/verify-log} — НЕ подпадает под permitAll {@code /api/v1/verify/**},
 * поэтому требует аутентификации (см. SecurityConfig).</p>
 */
@RestController
@RequestMapping("/api/v1/verify-log")
public class VerifyScanLogController {

    private final VerifyScanLogRepository repository;

    public VerifyScanLogController(VerifyScanLogRepository repository) {
        this.repository = repository;
    }

    /**
     * Последние проверки, новые сверху. С {@code number} — история конкретного ПЛ,
     * без него — общая лента. {@code limit} ограничен как у прочих списков (см. AuditController).
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('INSPECTOR','MINTRANS_ANALYST','SYSTEM_ADMIN')")
    public List<VerifyScanLog> list(@RequestParam(required = false) String number,
                                    @RequestParam(defaultValue = "100") int limit) {
        var pageable = PageRequest.of(0, Math.min(Math.max(limit, 1), 500));
        return (number == null || number.isBlank())
                ? repository.findAllByOrderByScannedAtDesc(pageable)
                : repository.findByWaybillNumberOrderByScannedAtDesc(number.trim(), pageable);
    }
}
