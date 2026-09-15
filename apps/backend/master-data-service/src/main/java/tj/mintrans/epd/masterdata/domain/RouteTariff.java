package tj.mintrans.epd.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Тариф маршрута (перенос {@code tariffs} из ИС «Роҳхат»): цена за машино-км и за поездку.
 *
 * <p>Отдельно от {@link Tariff} (тариф по типу ТС): у «Роҳхат» тариф привязан к маршруту
 * и виду топлива. {@code fuelId = null} — тариф для всех видов топлива маршрута.</p>
 */
@Entity
@Table(name = "route_tariff")
public class RouteTariff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "route_id", nullable = false)
    private UUID routeId;

    @Column(name = "fuel_id")
    private Short fuelId;

    private String number;

    @Column(name = "type_auto")
    private String typeAuto;

    /** Цена за 1 машино-км. */
    @Column(name = "price_per_1_mkm", nullable = false)
    private double pricePer1Mkm;

    /** Цена за одну поездку. */
    @Column(name = "price_one_time", nullable = false)
    private double priceOneTime;

    public Long getId() { return id; }
    public UUID getRouteId() { return routeId; }
    public void setRouteId(UUID routeId) { this.routeId = routeId; }
    public Short getFuelId() { return fuelId; }
    public void setFuelId(Short fuelId) { this.fuelId = fuelId; }
    public String getNumber() { return number; }
    public void setNumber(String number) { this.number = number; }
    public String getTypeAuto() { return typeAuto; }
    public void setTypeAuto(String typeAuto) { this.typeAuto = typeAuto; }
    public double getPricePer1Mkm() { return pricePer1Mkm; }
    public void setPricePer1Mkm(double pricePer1Mkm) { this.pricePer1Mkm = pricePer1Mkm; }
    public double getPriceOneTime() { return priceOneTime; }
    public void setPriceOneTime(double priceOneTime) { this.priceOneTime = priceOneTime; }
}
