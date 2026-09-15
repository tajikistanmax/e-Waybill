package tj.mintrans.epd.waybill.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Оплата путевого листа (AWAITING_PAYMENT → PAID). Одна запись на документ.
 * Подтверждение — платёжный шлюз (webhook, этап 1б) или бухгалтер (роль ACCOUNTANT).
 */
@Entity
@Table(name = "waybill_payment")
public class WaybillPayment {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    /** Оплата возвращена (§16 QA): CONFIRMED → REFUNDED, бухгалтером (роль ACCOUNTANT). */
    public static final String STATUS_REFUNDED = "REFUNDED";

    @Id
    private UUID id;

    @Column(name = "waybill_id", nullable = false, unique = true)
    private UUID waybillId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency = "TJS";

    @Column(nullable = false)
    private String status = STATUS_PENDING;

    /** BANK | GATEWAY | SUBSCRIPTION | CASH. */
    private String method;

    @Column(name = "external_ref")
    private String externalRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "confirmed_by")
    private String confirmedBy;

    // -------- возврат оплаты (CONFIRMED → REFUNDED), симметрично полям подтверждения --------

    @Column(name = "refund_reason")
    private String refundReason;

    @Column(name = "refund_amount")
    private BigDecimal refundAmount;

    @Column(name = "refunded_by")
    private String refundedBy;

    @Column(name = "refunded_at")
    private OffsetDateTime refundedAt;

    /** № возвратной транзакции внешнего шлюза (заполняется, когда подключат интеграцию). */
    @Column(name = "refund_external_ref")
    private String refundExternalRef;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getWaybillId() { return waybillId; }
    public void setWaybillId(UUID waybillId) { this.waybillId = waybillId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getExternalRef() { return externalRef; }
    public void setExternalRef(String externalRef) { this.externalRef = externalRef; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(OffsetDateTime confirmedAt) { this.confirmedAt = confirmedAt; }
    public String getConfirmedBy() { return confirmedBy; }
    public void setConfirmedBy(String confirmedBy) { this.confirmedBy = confirmedBy; }
    public String getRefundReason() { return refundReason; }
    public void setRefundReason(String refundReason) { this.refundReason = refundReason; }
    public BigDecimal getRefundAmount() { return refundAmount; }
    public void setRefundAmount(BigDecimal refundAmount) { this.refundAmount = refundAmount; }
    public String getRefundedBy() { return refundedBy; }
    public void setRefundedBy(String refundedBy) { this.refundedBy = refundedBy; }
    public OffsetDateTime getRefundedAt() { return refundedAt; }
    public void setRefundedAt(OffsetDateTime refundedAt) { this.refundedAt = refundedAt; }
    public String getRefundExternalRef() { return refundExternalRef; }
    public void setRefundExternalRef(String refundExternalRef) { this.refundExternalRef = refundExternalRef; }
}
