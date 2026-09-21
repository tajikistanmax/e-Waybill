package tj.mintrans.epd.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Пассажирский маршрут (хатсайр): рақам, номгӯ, тип ТС, регион; с V67 — также поля формы legacy
 * (MIGRATION.md 2.28): пункты А/Б, время одного рейса А/Б, срок свидетельства, город/район, координаты.
 *
 * <p>Коэффициентные и путевые поля (V28, перенос {@code routes} из ИС «Роҳхат») питают
 * расчёт нормы топлива и пассажирских показателей. ВНИМАНИЕ: {@code mountainCoefValue}
 * и {@code inCityCoefValue} — САМИ ЗНАЧЕНИЯ коэффициентов (5/10/15/20), не ключи справочника
 * (docs/spec/07-calculations.md §1.4.1).</p>
 */
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

    /**
     * Тип маршрута (городской/пригородный/междугородный/международный/транзитный) —
     * «мягкая» ссылка на {@link RouteType#getCode()} по числовому коду (жёсткого FK нет,
     * как у {@link #regionId}). Nullable: у старых маршрутов тип не задан. Справочник V63.
     */
    @Column(name = "route_type_code")
    private Short routeTypeCode;

    // ---------------------------------------------------- коэффициенты (V28)

    @Column(name = "winter_coef_id")
    private Long winterCoefId;

    /** ЗНАЧЕНИЕ горного коэффициента, % (не ключ справочника). */
    @Column(name = "mountain_coef_value")
    private Short mountainCoefValue;

    /** ЗНАЧЕНИЕ городского коэффициента, % (не ключ справочника). */
    @Column(name = "in_city_coef_value")
    private Short inCityCoefValue;

    @Column(name = "station_coef")
    private Short stationCoef;

    @Column(name = "road_quality")
    private Short roadQuality;

    /** «Душанбинская» ветка: норма из {@code brand.fuel_100_dushanbe}, коэффициенты не применяются. */
    @Column(name = "excluding_coef", nullable = false)
    private boolean excludingCoef;

    @Column(name = "additional_fuel_100")
    private Double additionalFuel100;

    @Column(name = "additional_fuel")
    private Double additionalFuel;

    @Column(name = "cond_fuel")
    private Double condFuel;

    @Column(name = "heating_fuel")
    private Double heatingFuel;

    // ---------------------------------------------------- путевые показатели (V28)

    @Column(name = "distance_a")
    private Double distanceA;

    @Column(name = "distance_b")
    private Double distanceB;

    @Column(name = "begin_path_a")
    private Double beginPathA;

    @Column(name = "begin_path_b")
    private Double beginPathB;

    @Column(name = "planned_lap")
    private Short plannedLap;

    @Column(name = "coe_use_capacity")
    private Double coeUseCapacity;

    @Column(name = "average_length_pass_seat")
    private Double averageLengthPassSeat;

    // ------------------------------------------- поля формы legacy без расчёта/печати (V67, 2.28)

    /** Номгӯи хатсайр A — конечный пункт А (legacy {@code name_a}); в печати — общее {@code name}. */
    @Column(name = "name_a")
    private String nameA;

    /** Номгӯи хатсайр B — конечный пункт Б (legacy {@code name_b}). */
    @Column(name = "name_b")
    private String nameB;

    /** Вақт дар як гардиш A — время одного рейса в направлении А (legacy {@code time_one_lap_a}). */
    @Column(name = "time_one_lap_a")
    private LocalTime timeOneLapA;

    /** Время одного рейса в направлении Б. */
    @Column(name = "time_one_lap_b")
    private LocalTime timeOneLapB;

    /** Муҳлати вобастакунии шаҳодатнома — срок действия свидетельства маршрута (legacy {@code valid_cert}). */
    @Column(name = "valid_cert")
    private LocalDate validCert;

    /** Шаҳру ноҳия — город/район (по справочнику {@link City}, хранится имя, как у организации). */
    @Column(name = "city_name")
    private String cityName;

    /** Координаты маршрута (legacy latitude/longitude — строки; здесь NUMERIC(10,6)). */
    private BigDecimal latitude;

    private BigDecimal longitude;

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
    public Short getRouteTypeCode() { return routeTypeCode; }
    public void setRouteTypeCode(Short routeTypeCode) { this.routeTypeCode = routeTypeCode; }

    public Long getWinterCoefId() { return winterCoefId; }
    public void setWinterCoefId(Long winterCoefId) { this.winterCoefId = winterCoefId; }
    public Short getMountainCoefValue() { return mountainCoefValue; }
    public void setMountainCoefValue(Short mountainCoefValue) { this.mountainCoefValue = mountainCoefValue; }
    public Short getInCityCoefValue() { return inCityCoefValue; }
    public void setInCityCoefValue(Short inCityCoefValue) { this.inCityCoefValue = inCityCoefValue; }
    public Short getStationCoef() { return stationCoef; }
    public void setStationCoef(Short stationCoef) { this.stationCoef = stationCoef; }
    public Short getRoadQuality() { return roadQuality; }
    public void setRoadQuality(Short roadQuality) { this.roadQuality = roadQuality; }
    public boolean isExcludingCoef() { return excludingCoef; }
    public void setExcludingCoef(boolean excludingCoef) { this.excludingCoef = excludingCoef; }
    public Double getAdditionalFuel100() { return additionalFuel100; }
    public void setAdditionalFuel100(Double additionalFuel100) { this.additionalFuel100 = additionalFuel100; }
    public Double getAdditionalFuel() { return additionalFuel; }
    public void setAdditionalFuel(Double additionalFuel) { this.additionalFuel = additionalFuel; }
    public Double getCondFuel() { return condFuel; }
    public void setCondFuel(Double condFuel) { this.condFuel = condFuel; }
    public Double getHeatingFuel() { return heatingFuel; }
    public void setHeatingFuel(Double heatingFuel) { this.heatingFuel = heatingFuel; }
    public Double getDistanceA() { return distanceA; }
    public void setDistanceA(Double distanceA) { this.distanceA = distanceA; }
    public Double getDistanceB() { return distanceB; }
    public void setDistanceB(Double distanceB) { this.distanceB = distanceB; }
    public Double getBeginPathA() { return beginPathA; }
    public void setBeginPathA(Double beginPathA) { this.beginPathA = beginPathA; }
    public Double getBeginPathB() { return beginPathB; }
    public void setBeginPathB(Double beginPathB) { this.beginPathB = beginPathB; }
    public Short getPlannedLap() { return plannedLap; }
    public void setPlannedLap(Short plannedLap) { this.plannedLap = plannedLap; }
    public Double getCoeUseCapacity() { return coeUseCapacity; }
    public void setCoeUseCapacity(Double coeUseCapacity) { this.coeUseCapacity = coeUseCapacity; }
    public Double getAverageLengthPassSeat() { return averageLengthPassSeat; }
    public void setAverageLengthPassSeat(Double averageLengthPassSeat) { this.averageLengthPassSeat = averageLengthPassSeat; }
    public String getNameA() { return nameA; }
    public void setNameA(String nameA) { this.nameA = nameA; }
    public String getNameB() { return nameB; }
    public void setNameB(String nameB) { this.nameB = nameB; }
    public LocalTime getTimeOneLapA() { return timeOneLapA; }
    public void setTimeOneLapA(LocalTime timeOneLapA) { this.timeOneLapA = timeOneLapA; }
    public LocalTime getTimeOneLapB() { return timeOneLapB; }
    public void setTimeOneLapB(LocalTime timeOneLapB) { this.timeOneLapB = timeOneLapB; }
    public LocalDate getValidCert() { return validCert; }
    public void setValidCert(LocalDate validCert) { this.validCert = validCert; }
    public String getCityName() { return cityName; }
    public void setCityName(String cityName) { this.cityName = cityName; }
    public BigDecimal getLatitude() { return latitude; }
    public void setLatitude(BigDecimal latitude) { this.latitude = latitude; }
    public BigDecimal getLongitude() { return longitude; }
    public void setLongitude(BigDecimal longitude) { this.longitude = longitude; }
}
