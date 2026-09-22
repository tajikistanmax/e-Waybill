package tj.mintrans.epd.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "organization")
public class Organization {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String rma;

    /**
     * РМА головной компании для филиала; {@code null} — головная компания либо
     * самостоятельная организация. Приходит из единой платформы при синхронизации.
     */
    @Column(name = "parent_rma")
    private String parentRma;

    private String kpp;

    @Column(nullable = false)
    private String name;

    @Column(name = "type_company", nullable = false)
    private short typeCompany = 1;

    @Column(name = "region_id")
    private Short regionId;

    @Column(name = "city_name")
    private String cityName;

    private String address;
    private String phone;
    private String email;

    @Column(name = "name_head")
    private String nameHead;

    private String bank;

    @Column(name = "license_from")
    private LocalDate licenseFrom;

    @Column(name = "license_to")
    private LocalDate licenseTo;

    /** № иҷозатномаи ҳамлу нақл (лицензии перевозчика) — графа бланка 5Б-БМ. */
    @Column(name = "carrier_license_number")
    private String carrierLicenseNumber;

    @Column(nullable = false)
    private boolean blocked;

    /** Обоснование блокировки (обязательно при /block); очищается при /unblock. */
    @Column(name = "block_reason")
    private String blockReason;

    /** Доля дохода компании в заработке водителя (доля, не проценты): 0.5 = 50 % (§2.6). */
    @Column(name = "percent_income")
    private Double percentIncome;

    /** Надбавка за 1-й класс водителя. */
    @Column(name = "cat_1")
    private Short cat1;

    @Column(name = "cat_2")
    private Short cat2;

    @Column(name = "cat_3")
    private Short cat3;

    /**
     * Разрешённые типы ПЛ — CSV имён {@code WaybillType} ({@code null} — без ограничения,
     * кроме вида субъекта и лицензии). Аналог per-user permissions «Роҳхат».
     */
    @Column(name = "allowed_waybill_types")
    private String allowedWaybillTypes;

    // --- Реквизиты для паритета с боевой формой company/create (MinTransRT) ---

    /** Форма собственности: 1=частная (Шахсӣ), 2=государственная (Давлатӣ). */
    private Short ownership;

    /** Координаты для карты. */
    private java.math.BigDecimal latitude;
    private java.math.BigDecimal longitude;

    /** № свидетельства о регистрации предприятия. */
    @Column(name = "registration_cert_number")
    private String registrationCertNumber;

    /** Выписка (иқтибос). */
    @Column(name = "extract_number")
    private String extractNumber;

    /** Свидетельство ААИ/НДС 18% (боевое aai). */
    @Column(name = "vat_cert_number")
    private String vatCertNumber;

    /** План: объём перевозок, тыс. пасс. */
    @Column(name = "plan_pass_volume")
    private java.math.BigDecimal planPassVolume;

    /** План: пассажирооборот, млн пасс. */
    @Column(name = "plan_pass_traffic")
    private java.math.BigDecimal planPassTraffic;

    /** «Рамзи корхона» — внутренний код предприятия (legacy {@code companies.number}). */
    @Column(name = "internal_number")
    private String internalNumber;

    /** «Харита» — отметка на карте: точки/описание (legacy {@code companies.points}). */
    @Column(name = "map_points")
    private String mapPoints;

    /** «Сӯзишворӣ» — предприятие само выдаёт топливо (legacy {@code companies.give_fuel}). */
    @Column(name = "give_fuel", nullable = false)
    private boolean giveFuel;

    /** PHYSICAL (физлицо) | IP (индивидуальный предприниматель) | LEGAL (юрлицо). */
    @Column(name = "subject_type", nullable = false)
    private String subjectType = "LEGAL";

    /** MANUAL (внесено вручную, dev) | UNIFIED (из единой платформы Минтранса). */
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
    public String getRma() { return rma; }
    public void setRma(String rma) { this.rma = rma; }
    public String getParentRma() { return parentRma; }
    public void setParentRma(String parentRma) { this.parentRma = parentRma; }
    public String getKpp() { return kpp; }
    public void setKpp(String kpp) { this.kpp = kpp; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public short getTypeCompany() { return typeCompany; }
    public void setTypeCompany(short typeCompany) { this.typeCompany = typeCompany; }
    public Short getRegionId() { return regionId; }
    public void setRegionId(Short regionId) { this.regionId = regionId; }
    public String getCityName() { return cityName; }
    public void setCityName(String cityName) { this.cityName = cityName; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getNameHead() { return nameHead; }
    public void setNameHead(String nameHead) { this.nameHead = nameHead; }
    public String getBank() { return bank; }
    public void setBank(String bank) { this.bank = bank; }
    public LocalDate getLicenseFrom() { return licenseFrom; }
    public void setLicenseFrom(LocalDate licenseFrom) { this.licenseFrom = licenseFrom; }
    public LocalDate getLicenseTo() { return licenseTo; }
    public void setLicenseTo(LocalDate licenseTo) { this.licenseTo = licenseTo; }
    public String getCarrierLicenseNumber() { return carrierLicenseNumber; }
    public void setCarrierLicenseNumber(String carrierLicenseNumber) { this.carrierLicenseNumber = carrierLicenseNumber; }
    public boolean isBlocked() { return blocked; }
    public void setBlocked(boolean blocked) { this.blocked = blocked; }
    public String getBlockReason() { return blockReason; }
    public void setBlockReason(String blockReason) { this.blockReason = blockReason; }
    public Double getPercentIncome() { return percentIncome; }
    public void setPercentIncome(Double percentIncome) { this.percentIncome = percentIncome; }
    public Short getCat1() { return cat1; }
    public void setCat1(Short cat1) { this.cat1 = cat1; }
    public Short getCat2() { return cat2; }
    public void setCat2(Short cat2) { this.cat2 = cat2; }
    public Short getCat3() { return cat3; }
    public void setCat3(Short cat3) { this.cat3 = cat3; }
    public String getAllowedWaybillTypes() { return allowedWaybillTypes; }
    public void setAllowedWaybillTypes(String allowedWaybillTypes) { this.allowedWaybillTypes = allowedWaybillTypes; }
    public Short getOwnership() { return ownership; }
    public void setOwnership(Short ownership) { this.ownership = ownership; }
    public java.math.BigDecimal getLatitude() { return latitude; }
    public void setLatitude(java.math.BigDecimal latitude) { this.latitude = latitude; }
    public java.math.BigDecimal getLongitude() { return longitude; }
    public void setLongitude(java.math.BigDecimal longitude) { this.longitude = longitude; }
    public String getRegistrationCertNumber() { return registrationCertNumber; }
    public void setRegistrationCertNumber(String registrationCertNumber) { this.registrationCertNumber = registrationCertNumber; }
    public String getExtractNumber() { return extractNumber; }
    public void setExtractNumber(String extractNumber) { this.extractNumber = extractNumber; }
    public String getVatCertNumber() { return vatCertNumber; }
    public void setVatCertNumber(String vatCertNumber) { this.vatCertNumber = vatCertNumber; }
    public String getInternalNumber() { return internalNumber; }
    public void setInternalNumber(String internalNumber) { this.internalNumber = internalNumber; }
    public String getMapPoints() { return mapPoints; }
    public void setMapPoints(String mapPoints) { this.mapPoints = mapPoints; }
    public boolean isGiveFuel() { return giveFuel; }
    public void setGiveFuel(boolean giveFuel) { this.giveFuel = giveFuel; }
    public java.math.BigDecimal getPlanPassVolume() { return planPassVolume; }
    public void setPlanPassVolume(java.math.BigDecimal planPassVolume) { this.planPassVolume = planPassVolume; }
    public java.math.BigDecimal getPlanPassTraffic() { return planPassTraffic; }
    public void setPlanPassTraffic(java.math.BigDecimal planPassTraffic) { this.planPassTraffic = planPassTraffic; }
    public String getSubjectType() { return subjectType; }
    public void setSubjectType(String subjectType) { this.subjectType = subjectType; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public OffsetDateTime getSyncedAt() { return syncedAt; }
    public void setSyncedAt(OffsetDateTime syncedAt) { this.syncedAt = syncedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
