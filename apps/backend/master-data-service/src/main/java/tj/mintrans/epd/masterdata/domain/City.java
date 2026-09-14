package tj.mintrans.epd.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Справочник городов/районов (перенос из боевого MinTransRT). Привязан к региону (1..7).
 * Источник подсказок для поля «Город» организации; справочные данные, засеяны миграцией V54.
 */
@Entity
@Table(name = "city")
public class City {

    @Id
    private UUID id;

    @Column(name = "region_id", nullable = false)
    private short regionId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public short getRegionId() { return regionId; }
    public void setRegionId(short regionId) { this.regionId = regionId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
