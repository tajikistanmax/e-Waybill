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
 * Справочник регионов (зон деятельности) РТ — «минтақаҳо». Заводит задел под региональную
 * отчётность/фильтры: до этого регион существовал только как «мягкий» числовой код
 * {@code region_id} (1..7) в других сущностях ({@link City#getRegionId()},
 * {@link Organization#getRegionId()}, {@link Route}, {@link Coefficient}) без справочной таблицы.
 *
 * <p>Ключ справочника — числовой {@code code} (1..7): те же 7 именованных зон, что использует
 * фронт (REGION_OPTIONS) и что заданы в spec/data/dictionaries.yaml (регионы):
 * 1=Душанбе, 2=ВМКБ (ГБАО), 3=Суғд, 4=Рашт, 5=Хатлон-Бохтар, 6=Хатлон-Кӯлоб, 7=Ҳисор.
 * Первичный ключ технический (UUID) — по образцу {@link City}/{@link ExternalCity}; связь с
 * {@code region_id} других сущностей — «мягкая» (по code), жёсткого FK намеренно нет, чтобы не
 * ломать существующие данные. Двуязычное наименование (name_ru обязательно, name_tj — при наличии).
 * Стартовые 7 регионов засеяны миграцией V62.</p>
 */
@Entity
@Table(name = "region")
public class Region {

    @Id
    private UUID id;

    /** Числовой код региона (1..7) — человеко-понятный естественный ключ, совпадает с region_id. */
    @Column(nullable = false, unique = true)
    private Short code;

    @Column(name = "name_ru", nullable = false)
    private String nameRu;

    @Column(name = "name_tj")
    private String nameTj;

    /** «Рамз» — статистический код зоны из «Роҳхат» (3501 Душанбе … 3590 ВМКБ), V85. */
    @Column(name = "stat_code")
    private String statCode;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public Short getCode() { return code; }
    public void setCode(Short code) { this.code = code; }
    public String getNameRu() { return nameRu; }
    public void setNameRu(String nameRu) { this.nameRu = nameRu; }
    public String getNameTj() { return nameTj; }
    public void setNameTj(String nameTj) { this.nameTj = nameTj; }
    public String getStatCode() { return statCode; }
    public void setStatCode(String statCode) { this.statCode = statCode; }
    public short getSortOrder() { return sortOrder; }
    public void setSortOrder(short sortOrder) { this.sortOrder = sortOrder; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
