package tj.mintrans.epd.waybill.service;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tj.mintrans.epd.waybill.domain.VerifyScanLog;
import tj.mintrans.epd.waybill.repository.VerifyScanLogRepository;

/**
 * Запись журнала публичных проверок ПЛ по QR (§18 QA).
 *
 * <p>Вызывается из анонимного {@code VerifyController} при каждом обращении к проверке
 * (успешном и нет). Три инварианта:</p>
 * <ul>
 *   <li>журналирование НЕ требует авторизации — сама проверка остаётся публичной;</li>
 *   <li>сбой записи журнала НЕ срывает ответ проверки — любое исключение проглатывается
 *       и логируется (WARN), метод никогда не пробрасывает наружу;</li>
 *   <li>извлечение IP/User-Agent — по образцу аудита §21 ({@code AuditService}):
 *       X-Forwarded-For → X-Real-IP → remoteAddr, значения усечены под длину колонок.</li>
 * </ul>
 */
@Service
public class VerifyScanLogService {

    private static final Logger log = LoggerFactory.getLogger(VerifyScanLogService.class);

    private final VerifyScanLogRepository repository;

    public VerifyScanLogService(VerifyScanLogRepository repository) {
        this.repository = repository;
    }

    /**
     * Фиксирует одно обращение к проверке QR. Никогда не бросает исключений —
     * ошибку записи проглатывает (лог WARN), чтобы не сорвать/не замедлить ответ verify.
     */
    public void record(String jti, String waybillNumber, String result, String onlineStatus,
                       HttpServletRequest request) {
        try {
            var entry = new VerifyScanLog();
            entry.setJti(trim(jti, 64));
            entry.setWaybillNumber(trim(waybillNumber, 32));
            entry.setResult(result);
            entry.setOnlineStatus(trim(onlineStatus, 32));
            entry.setClientIp(request != null ? trim(clientIp(request), 64) : null);
            entry.setUserAgent(request != null ? trim(request.getHeader("User-Agent"), 512) : null);
            repository.save(entry);
        } catch (RuntimeException e) {
            // Журнал проверок — вспомогательный след; его сбой не должен ломать публичную
            // проверку QR. Но потеря записи должна быть заметна в логах эксплуатации.
            log.warn("Не удалось записать журнал проверки QR: result={} number={} — запись потеряна: {}",
                    result, waybillNumber, e.toString());
        }
    }

    /**
     * IP клиента: за обратным прокси госЦОД реальный адрес в X-Forwarded-For
     * (берём первый — исходный клиент), иначе X-Real-IP, иначе remoteAddr.
     * Тот же способ, что и в {@code AuditService}/{@code RateLimitFilter}.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        return (realIp != null && !realIp.isBlank()) ? realIp.trim() : request.getRemoteAddr();
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
