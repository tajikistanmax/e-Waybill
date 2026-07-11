package tj.mintrans.epd.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Правило (политика) движка бизнес-правил. Уровень действия (scope_level) и его ключ
 * (scope_key): NATIONAL (scope_key=''), ORGANIZATION (scope_key=РМА), VEHICLE_TYPE
 * (scope_key=имя типа ПЛ). Разрешение: VEHICLE_TYPE > ORGANIZATION > NATIONAL.
 */
@Entity
@Table(name = "policy")
public class Policy {

    @Id
    private UUID id;

    @Column(name = "scope_level", nullable = false)
    private String scopeLevel;

    @Column(name = "scope_key", nullable = false)
    private String scopeKey = "";

    @Column(name = "rule_key", nullable = false)
    private String ruleKey;

    @Column(name = "rule_value", nullable = false)
    private String ruleValue;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (updatedAt == null) updatedAt = OffsetDateTime.now();
        if (scopeKey == null) scopeKey = "";
    }

    public UUID getId() { return id; }
    public String getScopeLevel() { return scopeLevel; }
    public void setScopeLevel(String scopeLevel) { this.scopeLevel = scopeLevel; }
    public String getScopeKey() { return scopeKey; }
    public void setScopeKey(String scopeKey) { this.scopeKey = scopeKey; }
    public String getRuleKey() { return ruleKey; }
    public void setRuleKey(String ruleKey) { this.ruleKey = ruleKey; }
    public String getRuleValue() { return ruleValue; }
    public void setRuleValue(String ruleValue) { this.ruleValue = ruleValue; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
