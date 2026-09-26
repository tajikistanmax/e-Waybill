package tj.mintrans.epd.waybill.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Справка пассажиру (маълумотнома) — платный документ о стоимости проезда.
 * Перенос {@code malumotnomas}; в e-Waybill привязана к организации-эмитенту.
 */
@Entity
@Table(name = "malumotnoma")
public class Malumotnoma {

    @Id
    private UUID id;

    /** Сквозной номер справки («Рақами маълумотнома»), продолжает нумерацию «Роҳхат». */
    @Column(nullable = false, updatable = false)
    private Long number;

    @Column(nullable = false)
    private String fio;

    /** Вид транспорта: 1 автобус, 3 микроавтобус, 4 легковой. */
    @Column(name = "transport_type_id", nullable = false)
    private short transportTypeId;

    /** 1 — льготная (детская) справка, цена ×0.5. */
    @Column(nullable = false)
    private short age;

    @Column(name = "organization_rma")
    private String organizationRma;

    @Column(name = "issuer_rma")
    private String issuerRma;

    @Column(name = "issuer_name")
    private String issuerName;

    @Column(name = "updater_name")
    private String updaterName;

    @Column(nullable = false)
    private BigDecimal price = BigDecimal.ZERO;

    @Column(name = "route_summary")
    private String routeSummary;

    /** Перенесена из архива «Роҳхат» — правке не подлежит. */
    @Column(nullable = false, updatable = false)
    private boolean legacy;

    @Column(name = "annulled_at")
    private OffsetDateTime annulledAt;

    @Column(name = "annulled_by")
    private String annulledBy;

    @Column(name = "annul_reason")
    private String annulReason;

    @OneToMany(mappedBy = "malumotnoma", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("position, id")
    private List<MalumotnomaLine> lines = new ArrayList<>();

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

    public void addLine(MalumotnomaLine line) {
        line.setMalumotnoma(this);
        line.setPosition((short) lines.size());
        lines.add(line);
    }

    public boolean isAnnulled() { return annulledAt != null; }

    public UUID getId() { return id; }
    public Long getNumber() { return number; }
    public void setNumber(Long number) { this.number = number; }
    public boolean isLegacy() { return legacy; }
    public OffsetDateTime getAnnulledAt() { return annulledAt; }
    public void setAnnulledAt(OffsetDateTime annulledAt) { this.annulledAt = annulledAt; }
    public String getAnnulledBy() { return annulledBy; }
    public void setAnnulledBy(String annulledBy) { this.annulledBy = annulledBy; }
    public String getAnnulReason() { return annulReason; }
    public void setAnnulReason(String annulReason) { this.annulReason = annulReason; }
    public String getFio() { return fio; }
    public void setFio(String fio) { this.fio = fio; }
    public short getTransportTypeId() { return transportTypeId; }
    public void setTransportTypeId(short transportTypeId) { this.transportTypeId = transportTypeId; }
    public short getAge() { return age; }
    public void setAge(short age) { this.age = age; }
    public String getOrganizationRma() { return organizationRma; }
    public void setOrganizationRma(String organizationRma) { this.organizationRma = organizationRma; }
    public String getIssuerRma() { return issuerRma; }
    public void setIssuerRma(String issuerRma) { this.issuerRma = issuerRma; }
    public String getIssuerName() { return issuerName; }
    public void setIssuerName(String issuerName) { this.issuerName = issuerName; }
    public String getUpdaterName() { return updaterName; }
    public void setUpdaterName(String updaterName) { this.updaterName = updaterName; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getRouteSummary() { return routeSummary; }
    public void setRouteSummary(String routeSummary) { this.routeSummary = routeSummary; }
    public List<MalumotnomaLine> getLines() { return lines; }
    public void setLines(List<MalumotnomaLine> lines) { this.lines = lines; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
