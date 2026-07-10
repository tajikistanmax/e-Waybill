package tj.mintrans.epd.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Нархнома (прейскурант): тариф за 1 км, сомони/км. fuel_type=NULL — для всех видов
 * топлива. Уникальный ключ: (transport_type, fuel_type).
 */
@Entity
@Table(name = "tariff")
public class Tariff {

    @Id
    private UUID id;

    @Column(name = "transport_type", nullable = false)
    private short transportType;

    @Column(name = "fuel_type")
    private Short fuelType;

    @Column(name = "price_per_km", nullable = false)
    private BigDecimal pricePerKm;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
    }

    public UUID getId() { return id; }
    public short getTransportType() { return transportType; }
    public void setTransportType(short transportType) { this.transportType = transportType; }
    public Short getFuelType() { return fuelType; }
    public void setFuelType(Short fuelType) { this.fuelType = fuelType; }
    public BigDecimal getPricePerKm() { return pricePerKm; }
    public void setPricePerKm(BigDecimal pricePerKm) { this.pricePerKm = pricePerKm; }
}
