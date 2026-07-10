package tj.mintrans.epd.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Норма расхода топлива, л/100км. brand=NULL — норма для всех марок данного типа ТС.
 * Уникальный ключ: (transport_type, brand).
 */
@Entity
@Table(name = "fuel_norm")
public class FuelNorm {

    @Id
    private UUID id;

    @Column(name = "transport_type", nullable = false)
    private short transportType;

    private String brand;

    @Column(name = "base_norm", nullable = false)
    private BigDecimal baseNorm;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
    }

    public UUID getId() { return id; }
    public short getTransportType() { return transportType; }
    public void setTransportType(short transportType) { this.transportType = transportType; }
    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }
    public BigDecimal getBaseNorm() { return baseNorm; }
    public void setBaseNorm(BigDecimal baseNorm) { this.baseNorm = baseNorm; }
}
