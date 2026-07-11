package tj.mintrans.epd.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Запись журнала аудита: неизменяемый след изменения конфигурации/справочника
 * (кто, когда, действие, сущность, старое → новое значение).
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    private UUID id;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Column
    private String actor;

    @Column(name = "actor_org")
    private String actorOrg;

    @Column(nullable = false)
    private String action;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_key")
    private String entityKey;

    @Column(name = "old_value")
    private String oldValue;

    @Column(name = "new_value")
    private String newValue;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (occurredAt == null) occurredAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getActorOrg() { return actorOrg; }
    public void setActorOrg(String actorOrg) { this.actorOrg = actorOrg; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public String getEntityKey() { return entityKey; }
    public void setEntityKey(String entityKey) { this.entityKey = entityKey; }
    public String getOldValue() { return oldValue; }
    public void setOldValue(String oldValue) { this.oldValue = oldValue; }
    public String getNewValue() { return newValue; }
    public void setNewValue(String newValue) { this.newValue = newValue; }
}
