package tj.mintrans.epd.masterdata.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.masterdata.domain.AuditLog;
import tj.mintrans.epd.masterdata.repository.AuditLogRepository;

/**
 * Разовая (идемпотентная — безопасно гонять при каждом старте) миграция: достраивает
 * hash-chain (ИБ-13.6.3, {@link AuditService}) для исторических записей журнала,
 * созданных до введения цепочки (2026-09-04, V44). Обходит журнал целиком в порядке
 * {@code seq} и для каждой записи без {@code recordHash} вычисляет его от текущего
 * состояния цепочки — так резюмируется корректно и при частичном сбое на середине.
 */
@Component
public class AuditHashChainMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AuditHashChainMigration.class);

    private final AuditLogRepository repository;

    public AuditHashChainMigration(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        repository.acquireHashChainLock();
        String prevHash = AuditService.GENESIS;
        int migrated = 0;
        int total = 0;
        try (var stream = repository.findAllByOrderBySeqAsc()) {
            for (AuditLog entry : (Iterable<AuditLog>) stream::iterator) {
                total++;
                if (entry.getRecordHash() != null) {
                    prevHash = entry.getRecordHash();
                    continue;
                }
                entry.setPrevHash(prevHash);
                String hash = AuditService.computeHash(prevHash, entry);
                entry.setRecordHash(hash);
                repository.save(entry);
                prevHash = hash;
                migrated++;
            }
        }
        if (migrated > 0) {
            log.warn("AuditHashChainMigration: достроена цепочка хешей для {} из {} исторических записей аудита",
                    migrated, total);
        } else {
            log.info("AuditHashChainMigration: цепочка хешей уже полна ({} записей), миграция не требуется", total);
        }
    }
}
