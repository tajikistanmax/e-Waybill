package tj.mintrans.epd.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Класс водителя и его коэффициент (перенос {@code drive_classes}). */
@Entity
@Table(name = "drive_class")
public class DriveClass {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "class", nullable = false)
    private String driveClass;

    private Short coef;

    public Long getId() { return id; }
    public String getDriveClass() { return driveClass; }
    public void setDriveClass(String driveClass) { this.driveClass = driveClass; }
    public Short getCoef() { return coef; }
    public void setCoef(Short coef) { this.coef = coef; }
}
