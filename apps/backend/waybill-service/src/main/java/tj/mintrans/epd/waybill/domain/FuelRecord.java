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

    /** Довыдано в пути (дозаправка сверх первоначальной выдачи), «Иловагӣ» бланка. */
    @Column(name = "additional_given")
    private BigDecimal additionalGiven;

    /** Возвращено на базу неиспользованное топливо, «Баргардонида шуд» бланка. */
    private BigDecimal returned;

    /**
     * Надбавка при температуре ниже 0 °C, л — «Коефитсенти ҳарорати аз 0 поён» legacy
     * ({@code fuels[].coef_below_0}). В расчёте прибавляется к выданному (helpers.php fuel_calc).
     */
    @Column(name = "coef_below_0")
    private BigDecimal coefBelow0;

    /**
     * Норма к выдаче, л — «Дода шавад» legacy ({@code fuels[].be_given}). Хранимое значение
     * (в оригинале префилл из предыдущего ПЛ того же ТС), в формулах расчёта не участвует.
     */
    @Column(name = "be_given")
    private BigDecimal beGiven;

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
    public BigDecimal getAdditionalGiven() { return additionalGiven; }
    public void setAdditionalGiven(BigDecimal additionalGiven) { this.additionalGiven = additionalGiven; }
    public BigDecimal getReturned() { return returned; }
    public void setReturned(BigDecimal returned) { this.returned = returned; }
    public BigDecimal getCoefBelow0() { return coefBelow0; }
    public void setCoefBelow0(BigDecimal coefBelow0) { this.coefBelow0 = coefBelow0; }
    public BigDecimal getBeGiven() { return beGiven; }
    public void setBeGiven(BigDecimal beGiven) { this.beGiven = beGiven; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
