package tj.mintrans.epd.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Строка выписки баланса организации — DEBIT (автосписание при выдаче ПЛ, Т1) или CREDIT
 * (пополнение SYSTEM_ADMIN по факту внешнего банковского перевода). {@code waybillId} уникален
 * среди DEBIT-строк (идемпотентность — см. миграцию V92, индекс uq_ledger_waybill_debit).
 */
@Entity
@Table(name = "billing_ledger")
public class BillingLedgerEntry {

    public static final String CREDIT = "CREDIT";
    public static final String DEBIT = "DEBIT";

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false)
    private String direction;

    @Column(name = "amount_tjs", nullable = false)
    private BigDecimal amountTjs;

    @Column(name = "waybill_id")
    private UUID waybillId;

    @Column(name = "waybill_type")
    private String waybillType;

    private String reference;

    private String note;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public BigDecimal getAmountTjs() { return amountTjs; }
    public void setAmountTjs(BigDecimal amountTjs) { this.amountTjs = amountTjs; }
    public UUID getWaybillId() { return waybillId; }
    public void setWaybillId(UUID waybillId) { this.waybillId = waybillId; }
    public String getWaybillType() { return waybillType; }
    public void setWaybillType(String waybillType) { this.waybillType = waybillType; }
    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
