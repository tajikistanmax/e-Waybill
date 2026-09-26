package tj.mintrans.epd.waybill.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Борхат — накладная замимаи 1 / 2 к путевому листу 2-Б (legacy {@code cargo_waybills}, V30).
 * Строк на лист может быть много; из них собираются P и Z грузового расчёта.
 */
@Entity
@Table(name = "consignment_note")
public class ConsignmentNote {

    @Id
    private UUID id;

    @Column(name = "waybill_id", nullable = false, updatable = false)
    private UUID waybillId;

    @Column(name = "work_day_id")
    private UUID workDayId;

    /** 1 — замимаи 1 (сдельная, P = масса·расстояние·рейсы); 2 — замимаи 2 (с экспедитором, P = масса·расстояние). */
    @Column(nullable = false)
    private short kind = 1;

    /** Сквозной номер борхата (legacy max+1), присваивается базой. */
    @Generated
    @Column(insertable = false, updatable = false)
    private Long number;

    @Column(name = "note_date", nullable = false)
    private LocalDate noteDate;

    @Column(name = "payer_id")
    private UUID payerId;
    @Column(name = "payer_name")
    private String payerName;
    @Column(name = "sender_id")
    private UUID senderId;
    @Column(name = "sender_name")
    private String senderName;
    @Column(name = "sender_address")
    private String senderAddress;
    @Column(name = "receiver_id")
    private UUID receiverId;
    @Column(name = "receiver_name")
    private String receiverName;
    @Column(name = "receiver_address")
    private String receiverAddress;
    @Column(name = "forwarder_id")
    private UUID forwarderId;
    @Column(name = "forwarder_name")
    private String forwarderName;
    @Column(name = "cargo_id")
    private UUID cargoId;
    @Column(name = "cargo_name")
    private String cargoName;
    @Column(name = "cargo_number")
    private Long cargoNumber;
    @Column(name = "cargo_amount")
    private BigDecimal cargoAmount;
    @Column(name = "cargo_weight")
    private BigDecimal cargoWeight;
    private BigDecimal distance;
    @Column(nullable = false)
    private Integer trips = 1;
    @Column(name = "special_distance")
    private BigDecimal specialDistance;
    @Column(name = "entry_time")
    private OffsetDateTime entryTime;
    @Column(name = "invoice_number")
    private String invoiceNumber;
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    /** Вклад строки в транспортную работу P, т·км (legacy calcP). */
    public double transportWork() {
        double w = weight(), d = distance == null ? 0 : distance.doubleValue();
        return kind == 2 ? w * d : w * d * Math.max(0, trips == null ? 0 : trips);
    }

    /** Вклад строки в число ездок Z (legacy calcZ: Σ рейсов замимаи 1 + число борхатов замимаи 2). */
    public double tripsCount() {
        return kind == 2 ? 1 : Math.max(0, trips == null ? 0 : trips);
    }

    /** Вклад в пробег со спецработой L1, км (legacy calcDistance_special_work: замимаи 1 — ×рейсы). */
    public double specialWork() {
        double l = specialDistance == null ? 0 : specialDistance.doubleValue();
        return kind == 2 ? l : l * Math.max(0, trips == null ? 0 : trips);
    }

    public double weight() {
        return cargoWeight == null ? 0 : cargoWeight.doubleValue();
    }

    public UUID getId() { return id; }
    public UUID getWaybillId() { return waybillId; }
    public void setWaybillId(UUID waybillId) { this.waybillId = waybillId; }
    public UUID getWorkDayId() { return workDayId; }
    public void setWorkDayId(UUID workDayId) { this.workDayId = workDayId; }
    public short getKind() { return kind; }
    public void setKind(short kind) { this.kind = kind; }
    public Long getNumber() { return number; }
    public LocalDate getNoteDate() { return noteDate; }
    public void setNoteDate(LocalDate noteDate) { this.noteDate = noteDate; }
    public UUID getPayerId() { return payerId; }
    public void setPayerId(UUID payerId) { this.payerId = payerId; }
    public String getPayerName() { return payerName; }
    public void setPayerName(String payerName) { this.payerName = payerName; }
    public UUID getSenderId() { return senderId; }
    public void setSenderId(UUID senderId) { this.senderId = senderId; }
    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }
    public String getSenderAddress() { return senderAddress; }
    public void setSenderAddress(String senderAddress) { this.senderAddress = senderAddress; }
    public UUID getReceiverId() { return receiverId; }
    public void setReceiverId(UUID receiverId) { this.receiverId = receiverId; }
    public String getReceiverName() { return receiverName; }
    public void setReceiverName(String receiverName) { this.receiverName = receiverName; }
    public String getReceiverAddress() { return receiverAddress; }
    public void setReceiverAddress(String receiverAddress) { this.receiverAddress = receiverAddress; }
    public UUID getForwarderId() { return forwarderId; }
    public void setForwarderId(UUID forwarderId) { this.forwarderId = forwarderId; }
    public String getForwarderName() { return forwarderName; }
    public void setForwarderName(String forwarderName) { this.forwarderName = forwarderName; }
    public UUID getCargoId() { return cargoId; }
    public void setCargoId(UUID cargoId) { this.cargoId = cargoId; }
    public String getCargoName() { return cargoName; }
    public void setCargoName(String cargoName) { this.cargoName = cargoName; }
    public Long getCargoNumber() { return cargoNumber; }
    public void setCargoNumber(Long cargoNumber) { this.cargoNumber = cargoNumber; }
    public BigDecimal getCargoAmount() { return cargoAmount; }
    public void setCargoAmount(BigDecimal cargoAmount) { this.cargoAmount = cargoAmount; }
    public BigDecimal getCargoWeight() { return cargoWeight; }
    public void setCargoWeight(BigDecimal cargoWeight) { this.cargoWeight = cargoWeight; }
    public BigDecimal getDistance() { return distance; }
    public void setDistance(BigDecimal distance) { this.distance = distance; }
    public Integer getTrips() { return trips; }
    public void setTrips(Integer trips) { this.trips = trips; }
    public BigDecimal getSpecialDistance() { return specialDistance; }
    public void setSpecialDistance(BigDecimal specialDistance) { this.specialDistance = specialDistance; }
    public OffsetDateTime getEntryTime() { return entryTime; }
    public void setEntryTime(OffsetDateTime entryTime) { this.entryTime = entryTime; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
