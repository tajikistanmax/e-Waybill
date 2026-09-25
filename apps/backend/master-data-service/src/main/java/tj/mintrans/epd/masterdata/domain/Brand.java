package tj.mintrans.epd.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Марка ТС с нормативами расхода топлива (перенос {@code brands} из ИС «Роҳхат»).
 *
 * <p>{@code fuel100} / {@code fuel100Dushanbe} / {@code fuelHour} — JSON-массивы нормативов
 * (элемент: {@code fuel_id, consumption, ton_for_100, s_for_rais, work_for_hour,
 * consumption_for_special}); разбираются {@code FuelNormCalculator} в waybill-service.</p>
 */
@Entity
@Table(name = "brand")
public class Brand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Код марки старой системы; 5-я цифра ≠ 0 → наличие прицепа (грузовые формы). */
    private String number;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String model = "";

    @Column(name = "type_id", nullable = false)
    private long typeId;

    private Integer capacity;

    private Double carrying;

    @Column(name = "cost_services")
    private Double costServices;

    @Column(name = "fuel_100")
    private String fuel100;

    @Column(name = "fuel_100_dushanbe")
    private String fuel100Dushanbe;

    @Column(name = "fuel_hour")
    private String fuelHour;

    @Column(name = "fuel_interior_heating")
    private Double fuelInteriorHeating;

    @Column(name = "tariff_rate")
    private Double tariffRate;

    /** «Вазни холис» — снаряжённая масса марки, т (legacy brands.net_weight; колонка V27 загружена Ф1). */
    @Column(name = "net_weight")
    private java.math.BigDecimal netWeight;

    public java.math.BigDecimal getNetWeight() { return netWeight; }
    public void setNetWeight(java.math.BigDecimal netWeight) { this.netWeight = netWeight; }

    public Long getId() { return id; }
    public String getNumber() { return number; }
    public void setNumber(String number) { this.number = number; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public long getTypeId() { return typeId; }
    public void setTypeId(long typeId) { this.typeId = typeId; }
    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer capacity) { this.capacity = capacity; }
    public Double getCarrying() { return carrying; }
    public void setCarrying(Double carrying) { this.carrying = carrying; }
    public Double getCostServices() { return costServices; }
    public void setCostServices(Double costServices) { this.costServices = costServices; }
    public String getFuel100() { return fuel100; }
    public void setFuel100(String fuel100) { this.fuel100 = fuel100; }
    public String getFuel100Dushanbe() { return fuel100Dushanbe; }
    public void setFuel100Dushanbe(String fuel100Dushanbe) { this.fuel100Dushanbe = fuel100Dushanbe; }
    public String getFuelHour() { return fuelHour; }
    public void setFuelHour(String fuelHour) { this.fuelHour = fuelHour; }
    public Double getFuelInteriorHeating() { return fuelInteriorHeating; }
    public void setFuelInteriorHeating(Double fuelInteriorHeating) { this.fuelInteriorHeating = fuelInteriorHeating; }
    public Double getTariffRate() { return tariffRate; }
    public void setTariffRate(Double tariffRate) { this.tariffRate = tariffRate; }
}
