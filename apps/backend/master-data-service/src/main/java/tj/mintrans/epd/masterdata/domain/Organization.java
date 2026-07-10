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

    @Column(nullable = false)
    private boolean blocked;

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
    public boolean isBlocked() { return blocked; }
    public void setBlocked(boolean blocked) { this.blocked = blocked; }
    public String getSubjectType() { return subjectType; }
    public void setSubjectType(String subjectType) { this.subjectType = subjectType; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public OffsetDateTime getSyncedAt() { return syncedAt; }
    public void setSyncedAt(OffsetDateTime syncedAt) { this.syncedAt = syncedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
