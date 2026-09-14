package tj.mintrans.epd.masterdata.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Коэффициент износа ТС (перенос {@code used_coef}).
 * Порог: возраст ТС строго {@code > year} И пробег строго {@code > km} → надбавка {@code coef}.
 */
@Entity
@Table(name = "used_coef")
public class UsedCoef {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Short year;

    private Integer km;

    private Short coef;

    public Long getId() { return id; }
    public Short getYear() { return year; }
    public void setYear(Short year) { this.year = year; }
    public Integer getKm() { return km; }
    public void setKm(Integer km) { this.km = km; }
    public Short getCoef() { return coef; }
    public void setCoef(Short coef) { this.coef = coef; }
}
