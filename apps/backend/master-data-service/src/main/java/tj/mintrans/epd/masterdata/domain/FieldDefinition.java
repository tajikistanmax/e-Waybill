package tj.mintrans.epd.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Определение дополнительного (кастомного) поля типа путевого листа.
 * Задаётся администратором Минтранса через конструктор полей: тип ПЛ (waybill_type),
 * машинный ключ (field_key), двуязычная подпись, тип данных и признак обязательности.
 */
@Entity
@Table(name = "field_definition")
public class FieldDefinition {

    @Id
    private UUID id;

    @Column(name = "waybill_type", nullable = false)
    private String waybillType;

    @Column(name = "field_key", nullable = false)
    private String fieldKey;

    @Column(name = "label_ru", nullable = false)
    private String labelRu;

    @Column(name = "label_tj")
    private String labelTj;

    @Column(name = "data_type", nullable = false)
    private String dataType;

    @Column(nullable = false)
    private boolean required;

    @Column(name = "options")
    private String options;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder;

    @Column(nullable = false)
    private boolean active = true;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
    }

    public UUID getId() { return id; }
    public String getWaybillType() { return waybillType; }
    public void setWaybillType(String waybillType) { this.waybillType = waybillType; }
    public String getFieldKey() { return fieldKey; }
    public void setFieldKey(String fieldKey) { this.fieldKey = fieldKey; }
    public String getLabelRu() { return labelRu; }
    public void setLabelRu(String labelRu) { this.labelRu = labelRu; }
    public String getLabelTj() { return labelTj; }
    public void setLabelTj(String labelTj) { this.labelTj = labelTj; }
    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public String getOptions() { return options; }
    public void setOptions(String options) { this.options = options; }
    public short getSortOrder() { return sortOrder; }
    public void setSortOrder(short sortOrder) { this.sortOrder = sortOrder; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
