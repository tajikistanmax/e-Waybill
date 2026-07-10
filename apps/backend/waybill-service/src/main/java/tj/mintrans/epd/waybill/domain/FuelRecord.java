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
 * Запись учёта топлива: привязана к путевому листу целиком или к конкретному
 * рабочему дню. Вид топлива — справочник (раздел 8 legacy-спеки):
 * 1=Бензин, 2=Солярка, 3=Газ сжиженный, 4=Газ природный, 5=Электро.
 */
@Entity
@Table(name = "fuel_record")
public class FuelRecord {

    @Id
    private UUID id;

    @Column(name = "waybill_id", nullable = false)
    private UUID waybillId;

    @Column(name = "work_day_id")
    private UUID workDayId;

    @Column(name = "fuel_type", nullable = false)
    private short fuelType;

    @Column(name = "fuel_given")
    private BigDecimal fuelGiven;

    @Column(name = "remain_before_exit")
    private BigDecimal remainBeforeExit;

    @Column(name = "remain_entry")
    private BigDecimal remainEntry;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getWaybillId() { return waybillId; }
    public void setWaybillId(UUID waybillId) { this.waybillId = waybillId; }
    public UUID getWorkDayId() { return workDayId; }
    public void setWorkDayId(UUID workDayId) { this.workDayId = workDayId; }
    public short getFuelType() { return fuelType; }
    public void setFuelType(short fuelType) { this.fuelType = fuelType; }
    public BigDecimal getFuelGiven() { return fuelGiven; }
    public void setFuelGiven(BigDecimal fuelGiven) { this.fuelGiven = fuelGiven; }
    public BigDecimal getRemainBeforeExit() { return remainBeforeExit; }
    public void setRemainBeforeExit(BigDecimal remainBeforeExit) { this.remainBeforeExit = remainBeforeExit; }
    public BigDecimal getRemainEntry() { return remainEntry; }
    public void setRemainEntry(BigDecimal remainEntry) { this.remainEntry = remainEntry; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
