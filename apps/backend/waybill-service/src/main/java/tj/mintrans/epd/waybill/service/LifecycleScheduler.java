package tj.mintrans.epd.waybill.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.repository.WaybillStatusEventRepository;
import tj.mintrans.epd.waybill.domain.WaybillStatusEvent;

import java.time.OffsetDateTime;
import java.util.EnumSet;

/**
 * Автоматические переходы статусной машины (waybill-statuses.yaml):
 *  - READY/ISSUED, срок истёк            → EXPIRED («не выдан/не активирован до конца срока»);
 *  - ACTIVE, срок + грейс-период истёк   → EXPIRED (флаг нарушения — не закрыт вовремя);
 *  - COMPLETED старше срока ретенции     → ARCHIVED (только чтение).
 */
@Component
@ConditionalOnProperty(name = "epd.lifecycle.enabled", havingValue = "true", matchIfMissing = true)
public class LifecycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(LifecycleScheduler.class);
    private static final String ACTOR = "system";

    private final WaybillRepository waybills;
    private final WaybillStatusEventRepository events;
    private final int expiryGraceHours;
    private final int archiveAfterDays;

    public LifecycleScheduler(WaybillRepository waybills,
                              WaybillStatusEventRepository events,
                              @Value("${epd.lifecycle.expiry-grace-hours:24}") int expiryGraceHours,
                              @Value("${epd.lifecycle.archive-after-days:1825}") int archiveAfterDays) {
        this.waybills = waybills;
        this.events = events;
        this.expiryGraceHours = expiryGraceHours;
        this.archiveAfterDays = archiveAfterDays;
    }

    @Scheduled(fixedDelayString = "${epd.lifecycle.check-interval-ms:600000}")
    @Transactional
    public void run() {
        expireOverdue();
        archiveOld();
    }

    void expireOverdue() {
        var now = OffsetDateTime.now();
        // Не выдан / не активирован до конца срока действия
        for (var wb : waybills.findByStatusInAndValidToBefore(
                EnumSet.of(WaybillStatus.READY, WaybillStatus.ISSUED), now)) {
            var from = wb.getStatus();
            wb.setStatus(WaybillStatus.EXPIRED);
            events.save(WaybillStatusEvent.of(wb.getId(), from, WaybillStatus.EXPIRED, ACTOR,
                    "Срок действия истёк: не активирован"));
            waybills.save(wb);
            log.info("ПЛ {} ({}) просрочен: не активирован до {}", wb.getId(), wb.getNumber(), wb.getValidTo());
        }
        // На линии, но не закрыт в срок + грейс-период — фиксируется как нарушение
        for (var wb : waybills.findByStatusInAndValidToBefore(
                EnumSet.of(WaybillStatus.ACTIVE), now.minusHours(expiryGraceHours))) {
            wb.setStatus(WaybillStatus.EXPIRED);
            events.save(WaybillStatusEvent.of(wb.getId(), WaybillStatus.ACTIVE, WaybillStatus.EXPIRED, ACTOR,
                    "Срок действия истёк: рейс не закрыт (грейс-период %d ч; нарушение)".formatted(expiryGraceHours)));
            waybills.save(wb);
            log.warn("ПЛ {} ({}) просрочен на линии — нарушение", wb.getId(), wb.getNumber());
        }
    }

    void archiveOld() {
        var threshold = OffsetDateTime.now().minusDays(archiveAfterDays);
        for (var wb : waybills.findByStatusAndUpdatedAtBefore(WaybillStatus.COMPLETED, threshold)) {
            wb.setStatus(WaybillStatus.ARCHIVED);
            events.save(WaybillStatusEvent.of(wb.getId(), WaybillStatus.COMPLETED, WaybillStatus.ARCHIVED, ACTOR,
                    "Архивирование по политике ретенции (%d дней)".formatted(archiveAfterDays)));
            waybills.save(wb);
        }
    }
}
