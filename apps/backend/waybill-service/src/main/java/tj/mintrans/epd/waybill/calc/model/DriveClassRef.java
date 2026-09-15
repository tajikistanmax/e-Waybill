package tj.mintrans.epd.waybill.calc.model;

/**
 * Запись справочника классов водителей ({@code drive_classes}).
 *
 * @param driveClass наименование класса ({@code drive_classes.class})
 * @param coef       коэффициент класса, % ({@code null} — не задан)
 */
public record DriveClassRef(String driveClass, Integer coef) {
}
