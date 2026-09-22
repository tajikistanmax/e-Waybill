package tj.mintrans.epd.masterdata.config;

/**
 * Выражения прав для {@code @PreAuthorize}, которые нужны в нескольких контроллерах.
 * Держим списком ролей, а не отрицанием: «кому можно» читается однозначно, а новая
 * роль по умолчанию не получает доступ.
 */
public final class Authorities {

    private Authorities() {
    }

    /**
     * Чтение справочников парка и персонала (ТС, водители, сотрудники). Внешние кабинеты
     * накладных (грузоотправитель, экспедитор, таможня) сюда не входят: им нужны только
     * свои накладные, а состав парка и персонала перевозчика — не их данные. До 22.09.2026
     * эти роли получали 200 (список выходил пустым из-за области арендатора, но сам доступ
     * был открыт) — находка сквозной приёмки, блок A5.
     */
    public static final String REGISTRY_READ = "hasAnyRole("
            + "'SYSTEM_ADMIN','API_INTEGRATOR','MINTRANS_ANALYST','INSPECTOR',"
            + "'COMPANY_ADMIN','BRANCH_ADMIN','DISPATCHER',"
            + "'DOCTOR','MECHANIC','DRIVER','ACCOUNTANT','FUEL_STATION')";
}
