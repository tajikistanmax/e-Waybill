package tj.mintrans.epd.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Элемент классификатора (справочника): страна, класс ADR, вид дозвола.
 * Двуязычное наименование (name_ru обязательно, name_tj — при наличии).
 */
@Entity
@Table(name = "classifier")
public class Classifier {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private String code;

    @Column(name = "name_ru", nullable = false)
    private String nameRu;

    @Column(name = "name_tj")
    private String nameTj;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder;

    @Column(nullable = false)
    private boolean active = true;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
    }

    public UUID getId() { return id; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getNameRu() { return nameRu; }
    public void setNameRu(String nameRu) { this.nameRu = nameRu; }
    public String getNameTj() { return nameTj; }
    public void setNameTj(String nameTj) { this.nameTj = nameTj; }
    public short getSortOrder() { return sortOrder; }
    public void setSortOrder(short sortOrder) { this.sortOrder = sortOrder; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
