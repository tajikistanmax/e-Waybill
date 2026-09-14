package tj.mintrans.epd.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "vehicle")
public class Vehicle {

    @Id
    private UUID id;

    @Column(name = "registration_number", nullable = false, unique = true)
    private String registrationNumber;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "transport_type", nullable = false)
    private short transportType;

    private String brand;

    @Column(name = "parking_number")
    private String parkingNumber;

    private Integer capacity;

    private BigDecimal carrying;

    @Column(nullable = false)
    private int odometer;

    private String vincode;

    /**
     * Вид топлива ТС: 1=Бензин, 2=Дизель, 3=Газ сжиженный, 4=Газ природный, 5=Электро —
     * тот же классификатор, что у FuelRecord. Определяет тариф (transport_type + fuel_type)
     * и вид топлива для расчёта, когда заправок по ПЛ ещё нет.
     */
    @Column(name = "fuel_type")
    private Short fuelType;

    /** Мощность двигателя, л.с. (ТЗ §6.4). */
    @Column(name = "engine_power")
    private Integer enginePower;

    @Column(name = "year_manufacture")
    private Short yearManufacture;

    @Column(name = "tech_inspection_valid_to")
    private LocalDate techInspectionValidTo;

    @Column(name = "control_card_valid_to")
    private LocalDate controlCardValidTo;

    /** № варақаи назоратӣ (контрольного листа ТС) — графа бланков ПЛ. */
    @Column(name = "control_card_number")
    private String controlCardNumber;

    /** № сертификата ТС для международных перевозок — графа бланка 5Б-БМ. */
    @Column(name = "intl_certificate_number")
    private String intlCertificateNumber;

    @Column(name = "insurance_valid_to")
    private LocalDate insuranceValidTo;

    /** ADR (ДОПОГ): свидетельство о допуске ТС к перевозке опасных грузов. */
    @Column(name = "adr_approval_valid_to")
    private LocalDate adrApprovalValidTo;

    // --- Реквизиты для паритета с боевой формой parking/create (MinTransRT) ---

    /** № муоинаи техникӣ (техосмотра). */
    @Column(name = "tech_inspection_number")
    private String techInspectionNumber;

    /** № шиносномаи техникӣ (техпаспорта). */
    @Column(name = "tech_passport_number")
    private String techPassportNumber;

    /** № сертификата ТС. */
    @Column(name = "certificate_number")
    private String certificateNumber;

    /** Кондиционер, % (боевое поле air_conditioner). */
    @Column(name = "air_conditioner")
    private Integer airConditioner;

    /** № контрольного листа для международной деятельности. */
    @Column(name = "intl_control_card_number")
    private String intlControlCardNumber;

    /** Срок междунар. контрольного листа. */
    @Column(name = "intl_control_card_valid_to")
    private LocalDate intlControlCardValidTo;

    /** Прицеп 1 (ядаки якум): госномер / марка / грузоподъёмность / вес (т). */
    @Column(name = "trailer1_number")
    private String trailer1Number;
    @Column(name = "trailer1_brand")
    private String trailer1Brand;
    @Column(name = "trailer1_carrying")
    private BigDecimal trailer1Carrying;
    @Column(name = "trailer1_weight")
    private BigDecimal trailer1Weight;

    /** Прицеп 2 (ядаки дуюм). */
    @Column(name = "trailer2_number")
    private String trailer2Number;
    @Column(name = "trailer2_brand")
    private String trailer2Brand;
    @Column(name = "trailer2_carrying")
    private BigDecimal trailer2Carrying;
    @Column(name = "trailer2_weight")
    private BigDecimal trailer2Weight;

    @Column(nullable = false)
    private boolean blocked;

    /** MANUAL | UNIFIED (данные объекта — из базы ГАИ через единую платформу). */
    @Column(nullable = false)
    private String source = "MANUAL";

    @Column(name = "synced_at")
    private OffsetDateTime syncedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        createdAt = OffsetDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public String getRegistrationNumber() { return registrationNumber; }
    public void setRegistrationNumber(String registrationNumber) { this.registrationNumber = registrationNumber; }
    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
    public short getTransportType() { return transportType; }
    public void setTransportType(short transportType) { this.transportType = transportType; }
    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }
    public String getParkingNumber() { return parkingNumber; }
    public void setParkingNumber(String parkingNumber) { this.parkingNumber = parkingNumber; }
    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer capacity) { this.capacity = capacity; }
    public BigDecimal getCarrying() { return carrying; }
    public void setCarrying(BigDecimal carrying) { this.carrying = carrying; }
    public int getOdometer() { return odometer; }
    public void setOdometer(int odometer) { this.odometer = odometer; }
    public String getVincode() { return vincode; }
    public void setVincode(String vincode) { this.vincode = vincode; }
    public Short getFuelType() { return fuelType; }
    public void setFuelType(Short fuelType) { this.fuelType = fuelType; }
    public Integer getEnginePower() { return enginePower; }
    public void setEnginePower(Integer enginePower) { this.enginePower = enginePower; }
    public Short getYearManufacture() { return yearManufacture; }
    public void setYearManufacture(Short yearManufacture) { this.yearManufacture = yearManufacture; }
    public LocalDate getTechInspectionValidTo() { return techInspectionValidTo; }
    public void setTechInspectionValidTo(LocalDate techInspectionValidTo) { this.techInspectionValidTo = techInspectionValidTo; }
    public LocalDate getControlCardValidTo() { return controlCardValidTo; }
    public void setControlCardValidTo(LocalDate controlCardValidTo) { this.controlCardValidTo = controlCardValidTo; }
    public String getControlCardNumber() { return controlCardNumber; }
    public void setControlCardNumber(String controlCardNumber) { this.controlCardNumber = controlCardNumber; }
    public String getIntlCertificateNumber() { return intlCertificateNumber; }
    public void setIntlCertificateNumber(String intlCertificateNumber) { this.intlCertificateNumber = intlCertificateNumber; }
    public LocalDate getInsuranceValidTo() { return insuranceValidTo; }
    public void setInsuranceValidTo(LocalDate insuranceValidTo) { this.insuranceValidTo = insuranceValidTo; }
    public LocalDate getAdrApprovalValidTo() { return adrApprovalValidTo; }
    public void setAdrApprovalValidTo(LocalDate adrApprovalValidTo) { this.adrApprovalValidTo = adrApprovalValidTo; }
    public String getTechInspectionNumber() { return techInspectionNumber; }
    public void setTechInspectionNumber(String techInspectionNumber) { this.techInspectionNumber = techInspectionNumber; }
    public String getTechPassportNumber() { return techPassportNumber; }
    public void setTechPassportNumber(String techPassportNumber) { this.techPassportNumber = techPassportNumber; }
    public String getCertificateNumber() { return certificateNumber; }
    public void setCertificateNumber(String certificateNumber) { this.certificateNumber = certificateNumber; }
    public Integer getAirConditioner() { return airConditioner; }
    public void setAirConditioner(Integer airConditioner) { this.airConditioner = airConditioner; }
    public String getIntlControlCardNumber() { return intlControlCardNumber; }
    public void setIntlControlCardNumber(String intlControlCardNumber) { this.intlControlCardNumber = intlControlCardNumber; }
    public LocalDate getIntlControlCardValidTo() { return intlControlCardValidTo; }
    public void setIntlControlCardValidTo(LocalDate intlControlCardValidTo) { this.intlControlCardValidTo = intlControlCardValidTo; }
    public String getTrailer1Number() { return trailer1Number; }
    public void setTrailer1Number(String trailer1Number) { this.trailer1Number = trailer1Number; }
    public String getTrailer1Brand() { return trailer1Brand; }
    public void setTrailer1Brand(String trailer1Brand) { this.trailer1Brand = trailer1Brand; }
    public BigDecimal getTrailer1Carrying() { return trailer1Carrying; }
    public void setTrailer1Carrying(BigDecimal trailer1Carrying) { this.trailer1Carrying = trailer1Carrying; }
    public BigDecimal getTrailer1Weight() { return trailer1Weight; }
    public void setTrailer1Weight(BigDecimal trailer1Weight) { this.trailer1Weight = trailer1Weight; }
    public String getTrailer2Number() { return trailer2Number; }
    public void setTrailer2Number(String trailer2Number) { this.trailer2Number = trailer2Number; }
    public String getTrailer2Brand() { return trailer2Brand; }
    public void setTrailer2Brand(String trailer2Brand) { this.trailer2Brand = trailer2Brand; }
    public BigDecimal getTrailer2Carrying() { return trailer2Carrying; }
    public void setTrailer2Carrying(BigDecimal trailer2Carrying) { this.trailer2Carrying = trailer2Carrying; }
    public BigDecimal getTrailer2Weight() { return trailer2Weight; }
    public void setTrailer2Weight(BigDecimal trailer2Weight) { this.trailer2Weight = trailer2Weight; }
    public boolean isBlocked() { return blocked; }
    public void setBlocked(boolean blocked) { this.blocked = blocked; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public OffsetDateTime getSyncedAt() { return syncedAt; }
    public void setSyncedAt(OffsetDateTime syncedAt) { this.syncedAt = syncedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
