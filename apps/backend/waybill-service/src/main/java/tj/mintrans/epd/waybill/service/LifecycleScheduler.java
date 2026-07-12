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
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final int expiryGraceHours;
    private final int archiveAfterDays;

    public LifecycleScheduler(WaybillRepository waybills,
                              WaybillStatusEventRepository events,
                              org.springframework.context.ApplicationEventPublisher eventPublisher,
                              @Value("${epd.lifecycle.expiry-grace-hours:24}") int expiryGraceHours,
                              @Value("${epd.lifecycle.archive-after-days:1825}") int archiveAfterDays) {
        this.waybills = waybills;
        this.events = events;
        this.eventPublisher = eventPublisher;
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
        // Документ не дошёл до линии к концу срока действия. CREATED/AWAITING_PAYMENT/PAID —
        // тоже OPEN_STATUSES: «зависший» после Т1 документ (осмотры/оплата не завершены)
        // иначе навсегда блокировал бы ТС и водителя (инвариант «один действующий ПЛ»).
        var pending = EnumSet.of(WaybillStatus.CREATED, WaybillStatus.AWAITING_PAYMENT,
                WaybillStatus.PAID, WaybillStatus.READY, WaybillStatus.ISSUED);
        for (var candidate : waybills.findByStatusInAndValidToBefore(pending, now)) {
            var wb = lockFresh(candidate.getId(), pending, now);
            if (wb == null) continue; // параллельная операция уже сменила статус — не трогаем
            var from = wb.getStatus();
            applyTransition(wb, from, "Срок действия истёк: документ не был активирован");
            log.info("ПЛ {} ({}) просрочен в статусе {}: срок до {}", wb.getId(), wb.getNumber(), from, wb.getValidTo());
        }
        // На линии, но не закрыт в срок + грейс-период — фиксируется как нарушение
        var graceEdge = now.minusHours(expiryGraceHours);
        for (var candidate : waybills.findByStatusInAndValidToBefore(EnumSet.of(WaybillStatus.ACTIVE), graceEdge)) {
            var wb = lockFresh(candidate.getId(), EnumSet.of(WaybillStatus.ACTIVE), graceEdge);
            if (wb == null) continue;
            applyTransition(wb, WaybillStatus.ACTIVE,
                    "Срок действия истёк: рейс не закрыт (грейс-период %d ч; нарушение)".formatted(expiryGraceHours));
            log.warn("ПЛ {} ({}) просрочен на линии — нарушение", wb.getId(), wb.getNumber());
        }
    }

    void archiveOld() {
        var threshold = OffsetDateTime.now().minusDays(archiveAfterDays);
        for (var candidate : waybills.findByStatusAndUpdatedAtBefore(WaybillStatus.COMPLETED, threshold)) {
            var wb = waybills.findByIdForUpdate(candidate.getId()).orElse(null);
            if (wb == null || wb.getStatus() != WaybillStatus.COMPLETED) continue;
            wb.setStatus(WaybillStatus.ARCHIVED);
            events.save(WaybillStatusEvent.of(wb.getId(), WaybillStatus.COMPLETED, WaybillStatus.ARCHIVED, ACTOR,
                    "Архивирование по политике ретенции (%d дней)".formatted(archiveAfterDays)));
            waybills.save(wb);
            publish(wb, WaybillStatus.COMPLETED, WaybillStatus.ARCHIVED, "Архивирование по политике ретенции");
        }
    }

    /**
     * Перечитать кандидата под блокировкой строки (FOR UPDATE) и перепроверить условие:
     * сериализация с пользовательскими мутациями (getForUpdate в WaybillService) — иначе
     * save() планировщика затирал бы параллельно выставленные флаги/статус (JPA пишет все колонки).
     */
    private tj.mintrans.epd.waybill.domain.Waybill lockFresh(java.util.UUID id,
            java.util.Set<WaybillStatus> statuses, OffsetDateTime validToBefore) {
        var wb = waybills.findByIdForUpdate(id).orElse(null);
        if (wb == null || !statuses.contains(wb.getStatus())) return null;
        if (wb.getValidTo() == null || !wb.getValidTo().isBefore(validToBefore)) return null;
        return wb;
    }

    private void applyTransition(tj.mintrans.epd.waybill.domain.Waybill wb, WaybillStatus from, String reason) {
        wb.setStatus(WaybillStatus.EXPIRED);
        events.save(WaybillStatusEvent.of(wb.getId(), from, WaybillStatus.EXPIRED, ACTOR, reason));
        waybills.save(wb);
        publish(wb, from, WaybillStatus.EXPIRED, reason);
    }

    /** Доменное событие — после commit KafkaEventBridge отправит его в topic epd.waybill.status. */
    private void publish(tj.mintrans.epd.waybill.domain.Waybill wb, WaybillStatus from, WaybillStatus to, String reason) {
        eventPublisher.publishEvent(new tj.mintrans.epd.waybill.event.WaybillStatusChanged(
                wb.getId(), wb.getNumber(), wb.getWaybillType().name(),
                wb.getOrganizationRma(), wb.getVehicleRegNumber(), wb.getDriverRma(),
                from.name(), to.name(), ACTOR, reason, wb.getSource(), OffsetDateTime.now()));
    }
}
