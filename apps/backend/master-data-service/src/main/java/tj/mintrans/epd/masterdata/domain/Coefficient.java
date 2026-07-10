package tj.mintrans.epd.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Коэффициент нормирования расхода. kind: WINTER (зимний, окно month_from..month_to),
 * CITY (внутригородской), HIGHLAND (высокогорный, по region_id), USAGE (эксплуатационный).
 */
@Entity
@Table(name = "coefficient")
public class Coefficient {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private String name;

    @Column(name = "value", nullable = false)
    private BigDecimal value;

    @Column(name = "region_id")
    private Short regionId;

    @Column(name = "month_from")
    private Short monthFrom;

    @Column(name = "month_to")
    private Short monthTo;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
    }

    public UUID getId() { return id; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getValue() { return value; }
    public void setValue(BigDecimal value) { this.value = value; }
    public Short getRegionId() { return regionId; }
    public void setRegionId(Short regionId) { this.regionId = regionId; }
    public Short getMonthFrom() { return monthFrom; }
    public void setMonthFrom(Short monthFrom) { this.monthFrom = monthFrom; }
    public Short getMonthTo() { return monthTo; }
    public void setMonthTo(Short monthTo) { this.monthTo = monthTo; }
}
