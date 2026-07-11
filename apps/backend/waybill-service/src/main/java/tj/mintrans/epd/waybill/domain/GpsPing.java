package tj.mintrans.epd.waybill.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * GPS-пинг транспортного средства: одна отметка положения ТС, присланная
 * устройством/трекером. recorded_at — время замера на устройстве,
 * received_at — время приёма платформой.
 */
@Entity
@Table(name = "gps_ping")
public class GpsPing {

    @Id
    private UUID id;

    @Column(name = "waybill_id")
    private UUID waybillId;

    @Column(name = "vehicle_reg_number", nullable = false)
    private String vehicleRegNumber;

    @Column(nullable = false)
    private BigDecimal lat;

    @Column(nullable = false)
    private BigDecimal lon;

    @Column(name = "speed_kmh")
    private Short speedKmh;

    @Column(name = "recorded_at", nullable = false)
    private OffsetDateTime recordedAt;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (receivedAt == null) receivedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getWaybillId() { return waybillId; }
    public void setWaybillId(UUID waybillId) { this.waybillId = waybillId; }
    public String getVehicleRegNumber() { return vehicleRegNumber; }
    public void setVehicleRegNumber(String vehicleRegNumber) { this.vehicleRegNumber = vehicleRegNumber; }
    public BigDecimal getLat() { return lat; }
    public void setLat(BigDecimal lat) { this.lat = lat; }
    public BigDecimal getLon() { return lon; }
    public void setLon(BigDecimal lon) { this.lon = lon; }
    public Short getSpeedKmh() { return speedKmh; }
    public void setSpeedKmh(Short speedKmh) { this.speedKmh = speedKmh; }
    public OffsetDateTime getRecordedAt() { return recordedAt; }
    public void setRecordedAt(OffsetDateTime recordedAt) { this.recordedAt = recordedAt; }
    public OffsetDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(OffsetDateTime receivedAt) { this.receivedAt = receivedAt; }
}
