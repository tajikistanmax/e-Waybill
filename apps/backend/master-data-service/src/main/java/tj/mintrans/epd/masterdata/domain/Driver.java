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
@Table(name = "driver")
public class Driver {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String rma;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "tab_number")
    private String tabNumber;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    /** Дата рождения (ТЗ §6.3): возраст в профиле врача и проверка несовершеннолетия. */
    @Column(name = "birth_date")
    private LocalDate birthDate;

    /** Общий стаж вождения, лет: правило «не менее 3 лет» для перевозки детей, поле схемы обмена. */
    @Column(name = "experience_years")
    private Short experienceYears;

    /**
     * Медограничения из водительского удостоверения («очки обязательны» и т. п.) — то, что
     * врач обязан видеть при предрейсовом осмотре. Это отметка на ВУ, а не диагноз:
     * группа крови, аллергии, хронические заболевания (спецкатегория ПДн) здесь не хранятся.
     */
    @Column(name = "med_restrictions")
    private String medRestrictions;

    @Column(name = "license_number")
    private String licenseNumber;

    @Column(name = "license_categories")
    private String licenseCategories;

    @Column(name = "license_valid_to")
    private LocalDate licenseValidTo;

    private Short degree;

    @Column(name = "med_cert_number")
    private String medCertNumber;

    @Column(name = "med_cert_valid_to")
    private LocalDate medCertValidTo;

    @Column(name = "safety_course_valid_to")
    private LocalDate safetyCourseValidTo;

    /** № талона курса 20 соатаи ҚҲР ва МО (БДД) — графа бланков ПЛ. */
    @Column(name = "safety_course_number")
    private String safetyCourseNumber;

    /** ДОПОГ (ADR): свидетельство о подготовке водителя к перевозке опасных грузов. */
    @Column(name = "adr_cert_valid_to")
    private LocalDate adrCertValidTo;

    private String phone;

    // --- Реквизиты для паритета с боевой формой driver/create (MinTransRT) ---

    /** Паспорт водителя (шиносномаи ронанда). */
    private String passport;

    /** Адрес (суроға). */
    private String address;

    private String email;

    /** Доверенность (ваколатнома). */
    @Column(name = "power_attorney")
    private String powerAttorney;

    /** Срок визы (муҳлати виза). */
    @Column(name = "visa_valid_to")
    private LocalDate visaValidTo;

    /** № договора (рақами шартнома). */
    @Column(name = "contract_number")
    private String contractNumber;

    /** Договор действует до (муҳлати шартнома то). */
    @Column(name = "contract_valid_to")
    private LocalDate contractValidTo;

    /** Закреплённое ТС (аналог vehicle.timesheet в боевой) — показывается в реестре водителей. */
    @Column(name = "assigned_vehicle_id")
    private UUID assignedVehicleId;

    @Column(nullable = false)
    private boolean suspended;

    /** MANUAL | UNIFIED (данные субъекта — из единой платформы: налоговая + ВУ из ГАИ). */
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
    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
    public String getTabNumber() { return tabNumber; }
    public void setTabNumber(String tabNumber) { this.tabNumber = tabNumber; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public LocalDate getBirthDate() { return birthDate; }
    public void setBirthDate(LocalDate birthDate) { this.birthDate = birthDate; }
    public Short getExperienceYears() { return experienceYears; }
    public void setExperienceYears(Short experienceYears) { this.experienceYears = experienceYears; }
    public String getMedRestrictions() { return medRestrictions; }
    public void setMedRestrictions(String medRestrictions) { this.medRestrictions = medRestrictions; }
    public String getLicenseNumber() { return licenseNumber; }
    public void setLicenseNumber(String licenseNumber) { this.licenseNumber = licenseNumber; }
    public String getLicenseCategories() { return licenseCategories; }
    public void setLicenseCategories(String licenseCategories) { this.licenseCategories = licenseCategories; }
    public LocalDate getLicenseValidTo() { return licenseValidTo; }
    public void setLicenseValidTo(LocalDate licenseValidTo) { this.licenseValidTo = licenseValidTo; }
    public Short getDegree() { return degree; }
    public void setDegree(Short degree) { this.degree = degree; }
    public String getMedCertNumber() { return medCertNumber; }
    public void setMedCertNumber(String medCertNumber) { this.medCertNumber = medCertNumber; }
    public LocalDate getMedCertValidTo() { return medCertValidTo; }
    public void setMedCertValidTo(LocalDate medCertValidTo) { this.medCertValidTo = medCertValidTo; }
    public LocalDate getSafetyCourseValidTo() { return safetyCourseValidTo; }
    public void setSafetyCourseValidTo(LocalDate safetyCourseValidTo) { this.safetyCourseValidTo = safetyCourseValidTo; }
    public String getSafetyCourseNumber() { return safetyCourseNumber; }
    public void setSafetyCourseNumber(String safetyCourseNumber) { this.safetyCourseNumber = safetyCourseNumber; }
    public LocalDate getAdrCertValidTo() { return adrCertValidTo; }
    public void setAdrCertValidTo(LocalDate adrCertValidTo) { this.adrCertValidTo = adrCertValidTo; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getPassport() { return passport; }
    public void setPassport(String passport) { this.passport = passport; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPowerAttorney() { return powerAttorney; }
    public void setPowerAttorney(String powerAttorney) { this.powerAttorney = powerAttorney; }
    public LocalDate getVisaValidTo() { return visaValidTo; }
    public void setVisaValidTo(LocalDate visaValidTo) { this.visaValidTo = visaValidTo; }
    public String getContractNumber() { return contractNumber; }
    public void setContractNumber(String contractNumber) { this.contractNumber = contractNumber; }
    public LocalDate getContractValidTo() { return contractValidTo; }
    public void setContractValidTo(LocalDate contractValidTo) { this.contractValidTo = contractValidTo; }
    public UUID getAssignedVehicleId() { return assignedVehicleId; }
    public void setAssignedVehicleId(UUID assignedVehicleId) { this.assignedVehicleId = assignedVehicleId; }
    public boolean isSuspended() { return suspended; }
    public void setSuspended(boolean suspended) { this.suspended = suspended; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public OffsetDateTime getSyncedAt() { return syncedAt; }
    public void setSyncedAt(OffsetDateTime syncedAt) { this.syncedAt = syncedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
