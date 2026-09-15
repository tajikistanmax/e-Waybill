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
 * Справочник ВНЕШНИХ (зарубежных) городов — источник подсказок для поля «Город» в международных
 * путевых листах и СМР (до этого город вводился свободным текстом). Аналог справочника городов РТ
 * ({@link City}), но привязан не к региону, а к стране: {@code country_code} — ISO 3166-1 alpha-2,
 * ссылка на элемент классификатора категории COUNTRY ({@link Classifier}); жёсткого FK нет, т.к.
 * у classifier уникальность по паре (category, code), а не по code (та же «мягкая» связь, что и
 * у {@code City.region_id}). Двуязычное наименование (name_ru обязательно, name_tj — при наличии).
 * Стартовый сид ключевых городов стран-партнёров засеян миграцией V60.
 */
@Entity
@Table(name = "external_city")
public class ExternalCity {

    @Id
    private UUID id;

    @Column(name = "country_code", nullable = false)
    private String countryCode;

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
    public String getCountryCode() { return countryCode; }
    public void setCountryCode(String countryCode) { this.countryCode = countryCode; }
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
