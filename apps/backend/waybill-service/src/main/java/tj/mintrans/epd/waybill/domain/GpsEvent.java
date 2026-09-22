package tj.mintrans.epd.waybill.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * GPS-событие Smart-city (legacy {@code gps_data}): заезд/выезд ТС на маршрут (направление А/Б) или
 * в/из предприятия (дистанция), привязанное к ПЛ дня. MIGRATION.md 9.7 / 8.9 / 12.12.
 */
@Entity
@Table(name = "gps_event")
public class GpsEvent {

    @Id
    private UUID id;

    @Column(name = "waybill_id", nullable = false)
    private UUID waybillId;

    @Column(name = "organization_rma", nullable = false)
    private String organizationRma;

    @Column(name = "organization_name")
    private String organizationName;

    @Column(name = "vehicle_reg_number", nullable = false)
    private String vehicleRegNumber;

    @Column(name = "driver_rma")
    private String driverRma;

    @Column(name = "driver_name")
    private String driverName;

    private String route;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GpsEventState state;

    private String direction;

    @Column(name = "distance_km")
    private BigDecimal distanceKm;

    @Column(name = "event_time", nullable = false)
    private OffsetDateTime eventTime;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (eventTime == null) eventTime = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getWaybillId() { return waybillId; }
    public void setWaybillId(UUID waybillId) { this.waybillId = waybillId; }
    public String getOrganizationRma() { return organizationRma; }
    public void setOrganizationRma(String organizationRma) { this.organizationRma = organizationRma; }
    public String getOrganizationName() { return organizationName; }
    public void setOrganizationName(String organizationName) { this.organizationName = organizationName; }
    public String getVehicleRegNumber() { return vehicleRegNumber; }
    public void setVehicleRegNumber(String vehicleRegNumber) { this.vehicleRegNumber = vehicleRegNumber; }
    public String getDriverRma() { return driverRma; }
    public void setDriverRma(String driverRma) { this.driverRma = driverRma; }
    public String getDriverName() { return driverName; }
    public void setDriverName(String driverName) { this.driverName = driverName; }
    public String getRoute() { return route; }
    public void setRoute(String route) { this.route = route; }
    public GpsEventState getState() { return state; }
    public void setState(GpsEventState state) { this.state = state; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public BigDecimal getDistanceKm() { return distanceKm; }
    public void setDistanceKm(BigDecimal distanceKm) { this.distanceKm = distanceKm; }
    public OffsetDateTime getEventTime() { return eventTime; }
    public void setEventTime(OffsetDateTime eventTime) { this.eventTime = eventTime; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
