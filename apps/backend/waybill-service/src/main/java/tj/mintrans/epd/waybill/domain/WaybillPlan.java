package tj.mintrans.epd.waybill.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Плановый показатель перевозок — год + вид ({@code PASSENGER}/{@code TAXI}/{@code CARGO}),
 * привязан к предприятию ({@code organizationRma}) либо республиканский ({@code organizationRma == null}).
 * Основа сравнения «план / факт» в сводном региональном отчёте Минтранса.
 */
@Entity
@Table(name = "waybill_plan")
public class WaybillPlan {

    @Id
    private UUID id;

    @Column(name = "organization_rma")
    private String organizationRma;

    @Column(name = "region_id")
    private Short regionId;

    @Column(name = "plan_year", nullable = false)
    private int planYear;

    /**
     * Месяц плана (1–12); {@code null} — план на весь год (как раньше, обратная
     * совместимость). Непустое значение — план на конкретный месяц (легаси Y-m),
     * приоритетный над годовым при построении отчёта за этот месяц.
     */
    @Column(name = "plan_month")
    private Short planMonth;

    @Column(name = "plan_kind", nullable = false)
    private String planKind = "PASSENGER";

    /** План объёма перевозок: тыс. пассажиров либо тыс. тонн. */
    @Column(name = "volume_thousand", nullable = false)
    private double volumeThousand;

    /** План оборота: млн пасс-км либо млн т-км. */
    @Column(name = "rotation_million", nullable = false)
    private double rotationMillion;

    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = OffsetDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public String getOrganizationRma() { return organizationRma; }
    public void setOrganizationRma(String organizationRma) { this.organizationRma = organizationRma; }
    public Short getRegionId() { return regionId; }
    public void setRegionId(Short regionId) { this.regionId = regionId; }
    public int getPlanYear() { return planYear; }
    public void setPlanYear(int planYear) { this.planYear = planYear; }
    public Short getPlanMonth() { return planMonth; }
    public void setPlanMonth(Short planMonth) { this.planMonth = planMonth; }
    public String getPlanKind() { return planKind; }
    public void setPlanKind(String planKind) { this.planKind = planKind; }
    public double getVolumeThousand() { return volumeThousand; }
    public void setVolumeThousand(double volumeThousand) { this.volumeThousand = volumeThousand; }
    public double getRotationMillion() { return rotationMillion; }
    public void setRotationMillion(double rotationMillion) { this.rotationMillion = rotationMillion; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
