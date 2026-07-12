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

    /** ДОПОГ (ADR): свидетельство о подготовке водителя к перевозке опасных грузов. */
    @Column(name = "adr_cert_valid_to")
    private LocalDate adrCertValidTo;

    private String phone;

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
    public LocalDate getAdrCertValidTo() { return adrCertValidTo; }
    public void setAdrCertValidTo(LocalDate adrCertValidTo) { this.adrCertValidTo = adrCertValidTo; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public boolean isSuspended() { return suspended; }
    public void setSuspended(boolean suspended) { this.suspended = suspended; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public OffsetDateTime getSyncedAt() { return syncedAt; }
    public void setSyncedAt(OffsetDateTime syncedAt) { this.syncedAt = syncedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
