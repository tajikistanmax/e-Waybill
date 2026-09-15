package tj.mintrans.epd.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Направление грузовой перевозки (перенос {@code directions} из ИС «Роҳхат»).
 *
 * <p>В отличие от {@link Route}, {@code mountainCoefId}/{@code inCityCoefId} здесь — НАСТОЯЩИЕ
 * КЛЮЧИ справочников {@code mountain_coef}/{@code city_coef}; значение ищется по справочнику
 * (docs/spec/07-calculations.md §3.4).</p>
 */
@Entity
@Table(name = "direction")
public class Direction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    private Integer number;

    @Column(name = "winter_coef_id")
    private Long winterCoefId;

    /** КЛЮЧ записи {@code mountain_coef}. */
    @Column(name = "mountain_coef_id")
    private Long mountainCoefId;

    /** КЛЮЧ записи {@code city_coef}. */
    @Column(name = "in_city_coef_id")
    private Long inCityCoefId;

    @Column(nullable = false)
    private boolean checked;

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Integer getNumber() { return number; }
    public void setNumber(Integer number) { this.number = number; }
    public Long getWinterCoefId() { return winterCoefId; }
    public void setWinterCoefId(Long winterCoefId) { this.winterCoefId = winterCoefId; }
    public Long getMountainCoefId() { return mountainCoefId; }
    public void setMountainCoefId(Long mountainCoefId) { this.mountainCoefId = mountainCoefId; }
    public Long getInCityCoefId() { return inCityCoefId; }
    public void setInCityCoefId(Long inCityCoefId) { this.inCityCoefId = inCityCoefId; }
    public boolean isChecked() { return checked; }
    public void setChecked(boolean checked) { this.checked = checked; }
}
