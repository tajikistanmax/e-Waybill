package tj.mintrans.epd.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Груз (бор) — справочник грузов для грузовых путевых листов (перенос {@code cargos} из ИС «Роҳхат»,
 * database/migrations/2022_06_24_204806_create_cargo_table.php).
 *
 * <p>В отличие от {@link Client}, ПЛАТФОРМЕННЫЙ, не привязан к организации: в эталоне таблица
 * {@code cargos} не имеет колонки организации/компании, доступ к CRUD регулируется ролью
 * (Backpack {@code role:superadmin|region|company}), а не мультитенантностью по РМА — один общий
 * справочник грузов для всех перевозчиков. Поле {@code number} эталона — сквозной автономер
 * (legacy {@code Cargo::creating: number = max+1}), печатается в борхате (прил. 1/2, «Рамз»);
 * с V65 выдаётся БД (sequence), MIGRATION.md 2.25.</p>
 */
@Entity
@Table(name = "cargo")
public class Cargo {

    @Id
    private UUID id;

    /** Рамзи бор — сквозной номер груза; присваивает БД при вставке (V65), в API только на чтение. */
    @Column(name = "number", insertable = false, updatable = false)
    @org.hibernate.annotations.Generated
    private Long number;

    @Column(nullable = false)
    private String name;

    /** Намуди бор — категория груза (свободный текст в эталоне, не отдельный справочник). */
    private String type;

    /** Воҳиди ченак — единица измерения (кг, т, м³, шт …). */
    private String unit;

    private BigDecimal price;

    /** Класи бор: 1-4 в эталоне (Backpack select), null — не задан. */
    @Column(name = "cargo_class")
    private Short cargoClass;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
    }

    public UUID getId() { return id; }
    public Long getNumber() { return number; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public Short getCargoClass() { return cargoClass; }
    public void setCargoClass(Short cargoClass) { this.cargoClass = cargoClass; }
}
