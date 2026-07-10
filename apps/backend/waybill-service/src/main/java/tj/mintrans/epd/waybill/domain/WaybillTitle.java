package tj.mintrans.epd.waybill.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Подписанный титул путевого листа (Т1–Т6). Неизменяем после создания —
 * корректировки оформляются новыми титулами типа CORRECTION.
 */
@Entity
@Table(name = "waybill_title")
public class WaybillTitle {

    @Id
    private UUID id;

    @Column(name = "waybill_id", nullable = false)
    private UUID waybillId;

    @Column(name = "title_type", nullable = false)
    private String titleType;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> data;

    @Column(name = "signer_rma", nullable = false)
    private String signerRma;

    @Column(name = "signer_role", nullable = false)
    private String signerRole;

    private String signature;

    @Column(name = "signed_at", nullable = false)
    private OffsetDateTime signedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (signedAt == null) signedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getWaybillId() { return waybillId; }
    public void setWaybillId(UUID waybillId) { this.waybillId = waybillId; }
    public String getTitleType() { return titleType; }
    public void setTitleType(String titleType) { this.titleType = titleType; }
    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }
    public String getSignerRma() { return signerRma; }
    public void setSignerRma(String signerRma) { this.signerRma = signerRma; }
    public String getSignerRole() { return signerRole; }
    public void setSignerRole(String signerRole) { this.signerRole = signerRole; }
    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }
    public OffsetDateTime getSignedAt() { return signedAt; }
}
