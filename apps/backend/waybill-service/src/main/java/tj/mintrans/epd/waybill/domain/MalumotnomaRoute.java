package tj.mintrans.epd.waybill.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Тарифицированный маршрут для справок пассажирам (перенос {@code routemalumotnomas}).
 * Отдельные цены по видам транспорта; не путать с {@code route} движка расчёта ПЛ.
 */
@Entity
@Table(name = "malumotnoma_route")
public class MalumotnomaRoute {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "distance_km", nullable = false)
    private double distanceKm;

    /** Цена для легкового (вид 4). */
    @Column(name = "car_price", nullable = false)
    private BigDecimal carPrice = BigDecimal.ZERO;

    /** Цена для микроавтобуса (вид 3). */
    @Column(name = "mbus_price", nullable = false)
    private BigDecimal mbusPrice = BigDecimal.ZERO;

    /** Цена для автобуса (вид 1). */
    @Column(name = "bus_price", nullable = false)
    private BigDecimal busPrice = BigDecimal.ZERO;

    @Column(nullable = false)
    private boolean active = true;

    /** id в routemalumotnomas «Роҳхат» — ключ переноса справочника и архива справок. */
    @Column(name = "legacy_id", updatable = false, insertable = false)
    private Integer legacyId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = OffsetDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    /** Цена для вида транспорта справки (1 автобус / 3 микроавтобус / 4 легковой; иначе 0). */
    public BigDecimal priceFor(int transportTypeId) {
        return switch (transportTypeId) {
            case 1 -> busPrice;
            case 3 -> mbusPrice;
            case 4 -> carPrice;
            default -> BigDecimal.ZERO;
        };
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public double getDistanceKm() { return distanceKm; }
    public void setDistanceKm(double distanceKm) { this.distanceKm = distanceKm; }
    public BigDecimal getCarPrice() { return carPrice; }
    public void setCarPrice(BigDecimal carPrice) { this.carPrice = carPrice; }
    public BigDecimal getMbusPrice() { return mbusPrice; }
    public void setMbusPrice(BigDecimal mbusPrice) { this.mbusPrice = mbusPrice; }
    public BigDecimal getBusPrice() { return busPrice; }
    public void setBusPrice(BigDecimal busPrice) { this.busPrice = busPrice; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Integer getLegacyId() { return legacyId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
