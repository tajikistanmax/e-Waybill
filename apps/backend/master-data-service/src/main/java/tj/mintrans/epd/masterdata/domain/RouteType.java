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
 * Справочник типов маршрутов — «навъҳои хатсайр» (аналог RouteType из ИС «Роҳхат»).
 * Классифицирует пассажирский {@link Route} по дальности/характеру сообщения:
 * городской, пригородный, междугородный, международный, транзитный.
 *
 * <p>Ключ справочника — числовой {@code code} (естественный, человеко-понятный), по образцу
 * {@link Region}. Первичный ключ технический (UUID). Связь с {@link Route} «мягкая» — по
 * {@code route.route_type_code}, жёсткого FK намеренно нет (тот же приём, что и у
 * {@code city.region_id}), чтобы не ломать существующие данные маршрутов. Двуязычное
 * наименование (name_ru обязательно, name_tj — при наличии). Стартовые типы засеяны
 * миграцией V63.</p>
 */
@Entity
@Table(name = "route_type")
public class RouteType {

    @Id
    private UUID id;

    /** Числовой код типа маршрута — человеко-понятный естественный ключ (совпадает с route.route_type_code). */
    @Column(nullable = false, unique = true)
    private Short code;

    @Column(name = "name_ru", nullable = false)
    private String nameRu;

    @Column(name = "name_tj")
    private String nameTj;

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
    public short getSortOrder() { return sortOrder; }
    public void setSortOrder(short sortOrder) { this.sortOrder = sortOrder; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
