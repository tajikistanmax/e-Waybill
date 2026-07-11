package tj.mintrans.epd.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.util.UUID;

/** Пассажирский маршрут (хатсайр): рақам, номгӯ, тип ТС, регион. */
@Entity
@Table(name = "route")
public class Route {

    @Id
    private UUID id;

    /** РМА организации-владельца: маршруты ведутся по каждому перевозчику отдельно. */
    @Column(name = "organization_rma", nullable = false)
    private String organizationRma;

    @Column(nullable = false)
    private String number;

    @Column(nullable = false)
    private String name;

    @Column(name = "transport_type")
    private Short transportType;

    @Column(name = "region_id")
    private Short regionId;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
    }

    public UUID getId() { return id; }
    public String getOrganizationRma() { return organizationRma; }
    public void setOrganizationRma(String organizationRma) { this.organizationRma = organizationRma; }
    public String getNumber() { return number; }
    public void setNumber(String number) { this.number = number; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Short getTransportType() { return transportType; }
    public void setTransportType(Short transportType) { this.transportType = transportType; }
    public Short getRegionId() { return regionId; }
    public void setRegionId(Short regionId) { this.regionId = regionId; }
}
