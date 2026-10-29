package tj.mintrans.epd.waybill.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.repository.VerifyScanLogRepository;

import java.time.OffsetDateTime;

/**
 * Ретенция журнала публичных проверок ПЛ по QR ({@code verify_scan_log}, §18 QA).
 *
 * <p>Таблица пишется на КАЖДЫЙ анонимный скан (успешный и нет) и без очистки растёт
 * неограниченно (найдено на security-аудите, go-live чек-лист п. 4.4). Срок хранения —
 * продуктовое/юридическое решение (это лог обращений к гос-документу, не техническая
 * мелочь), поэтому по умолчанию ВЫКЛЮЧЕНО ({@code epd.verify-scan-log.retention-days=0}):
 * ничего не удаляется, пока владелец/юристы явно не укажут срок.</p>
 */
@Component
public class VerifyScanLogRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(VerifyScanLogRetentionScheduler.class);

    private final VerifyScanLogRepository repository;
    private final int retentionDays;

    public VerifyScanLogRetentionScheduler(VerifyScanLogRepository repository,
            @Value("${epd.verify-scan-log.retention-days:0}") int retentionDays) {
        this.repository = repository;
        this.retentionDays = retentionDays;
    }

    /** Раз в сутки по умолчанию — журнал не настолько горячий, чтобы чаще. */
    @Scheduled(cron = "${epd.verify-scan-log.retention-cron:0 30 3 * * *}")
    @Transactional
    public void run() {
        if (retentionDays <= 0) return; // не настроено — ничего не трогаем (дефолт)
        var cutoff = OffsetDateTime.now().minusDays(retentionDays);
        long deleted = repository.deleteByScannedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("Ретенция verify_scan_log: удалено {} записей старше {} дней (cutoff={})",
                    deleted, retentionDays, cutoff);
        }
    }
}
