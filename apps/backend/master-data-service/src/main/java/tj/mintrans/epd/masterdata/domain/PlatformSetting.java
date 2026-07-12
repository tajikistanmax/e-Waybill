package tj.mintrans.epd.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Настройка платформы (§29 ТЗ). Самоописываемая: несёт тип значения и двуязычную метку,
 * чтобы фронт рендерил форму обобщённо. Уникальна в паре (category, setting_key).
 */
@Entity
@Table(name = "platform_setting")
public class PlatformSetting {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String category;

    @Column(name = "setting_key", nullable = false)
    private String settingKey;

    @Column(name = "value_type", nullable = false)
    private String valueType = "STRING";

    @Column(name = "setting_value")
    private String settingValue;

    @Column
    private String options;

    @Column(name = "name_ru", nullable = false)
    private String nameRu;

    @Column(name = "name_tj")
    private String nameTj;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder;

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
    }

    public UUID getId() { return id; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getSettingKey() { return settingKey; }
    public void setSettingKey(String settingKey) { this.settingKey = settingKey; }
    public String getValueType() { return valueType; }
    public void setValueType(String valueType) { this.valueType = valueType; }
    public String getSettingValue() { return settingValue; }
    public void setSettingValue(String settingValue) { this.settingValue = settingValue; }
    public String getOptions() { return options; }
    public void setOptions(String options) { this.options = options; }
    public String getNameRu() { return nameRu; }
    public void setNameRu(String nameRu) { this.nameRu = nameRu; }
    public String getNameTj() { return nameTj; }
    public void setNameTj(String nameTj) { this.nameTj = nameTj; }
    public short getSortOrder() { return sortOrder; }
    public void setSortOrder(short sortOrder) { this.sortOrder = sortOrder; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
