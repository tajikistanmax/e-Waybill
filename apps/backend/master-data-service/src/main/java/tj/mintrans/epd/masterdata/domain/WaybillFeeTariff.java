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
 * Ставка платы за выдачу путевого листа по типу: {@code organizationId == null} — национальная
 * ставка по умолчанию, иначе — переопределение для конкретной организации (решение владельца
 * 31.10.2026 о платной выдаче, пост-оплата). Назначает только SYSTEM_ADMIN (см.
 * {@code WaybillFeeTariffController}) — в отличие от движка политик, здесь организация не может
 * сама себе назначить ставку.
 */
@Entity
@Table(name = "waybill_fee_tariff")
public class WaybillFeeTariff {

    @Id
    private UUID id;

    @Column(name = "waybill_type", nullable = false)
    private String waybillType;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "amount_tjs", nullable = false)
    private BigDecimal amountTjs;

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (updatedAt == null) updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public String getWaybillType() { return waybillType; }
    public void setWaybillType(String waybillType) { this.waybillType = waybillType; }
    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
    public BigDecimal getAmountTjs() { return amountTjs; }
    public void setAmountTjs(BigDecimal amountTjs) { this.amountTjs = amountTjs; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
