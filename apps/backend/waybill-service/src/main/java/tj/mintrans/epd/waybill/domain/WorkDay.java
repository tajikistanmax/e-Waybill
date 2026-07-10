package tj.mintrans.epd.waybill.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Рабочий день многодневного путевого листа (legacy-формы 1-А/2-Б: по строке на день).
 * Не более одной записи на дату; число дней ограничено maxValidityDays типа ПЛ.
 */
@Entity
@Table(name = "work_day")
public class WorkDay {

    @Id
    private UUID id;

    @Column(name = "waybill_id", nullable = false)
    private UUID waybillId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "exit_time")
    private LocalTime exitTime;

    @Column(name = "entry_time")
    private LocalTime entryTime;

    @Column(name = "odometer_exit")
    private Integer odometerExit;

    @Column(name = "odometer_entry")
    private Integer odometerEntry;

    private Integer laps;

    private BigDecimal revenue;

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
    public LocalDate getWorkDate() { return workDate; }
    public void setWorkDate(LocalDate workDate) { this.workDate = workDate; }
    public LocalTime getExitTime() { return exitTime; }
    public void setExitTime(LocalTime exitTime) { this.exitTime = exitTime; }
    public LocalTime getEntryTime() { return entryTime; }
    public void setEntryTime(LocalTime entryTime) { this.entryTime = entryTime; }
    public Integer getOdometerExit() { return odometerExit; }
    public void setOdometerExit(Integer odometerExit) { this.odometerExit = odometerExit; }
    public Integer getOdometerEntry() { return odometerEntry; }
    public void setOdometerEntry(Integer odometerEntry) { this.odometerEntry = odometerEntry; }
    public Integer getLaps() { return laps; }
    public void setLaps(Integer laps) { this.laps = laps; }
    public BigDecimal getRevenue() { return revenue; }
    public void setRevenue(BigDecimal revenue) { this.revenue = revenue; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
