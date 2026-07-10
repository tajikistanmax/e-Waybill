package tj.mintrans.epd.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Сотрудник, подписывающий титулы: 1 — врач (духтур), 2 — механик, 3 — диспетчер (танзимгар).
 */
@Entity
@Table(name = "employee")
public class Employee {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String rma;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "tab_number")
    private String tabNumber;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private short type;

    private String phone;

    /** MANUAL | UNIFIED (ФИО субъекта — из единой платформы; роль в организации — локальная). */
    @Column(nullable = false)
    private String source = "MANUAL";

    @Column(name = "synced_at")
    private OffsetDateTime syncedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        createdAt = OffsetDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public String getRma() { return rma; }
    public void setRma(String rma) { this.rma = rma; }
    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
    public String getTabNumber() { return tabNumber; }
    public void setTabNumber(String tabNumber) { this.tabNumber = tabNumber; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public short getType() { return type; }
    public void setType(short type) { this.type = type; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public OffsetDateTime getSyncedAt() { return syncedAt; }
    public void setSyncedAt(OffsetDateTime syncedAt) { this.syncedAt = syncedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
