package tj.mintrans.epd.waybill.domain;

/**
 * Типы путевых листов (spec/data/waybill-types.yaml) с legacy-маппингом и кодом для национального номера.
 */
public enum WaybillType {
    WB_CAR("3-С", "01", 7),
    WB_TAXI("3-С такси", "02", 7),
    WB_MINIBUS("1-А", "03", 4),
    WB_BUS("Т(1-АД)", "04", 1),
    WB_TROLLEYBUS("Т(1-АД) троллейбус", "05", 1),
    WB_TRUCK("2-Б", "06", 15),
    WB_TRUCK_INTL("5Б-БМ", "07", 30),
    WB_PAX_INTL("4М-БМ", "08", 30),
    WB_SPECIAL("спецтехника", "09", 7),
    WB_DANGEROUS("опасные грузы", "10", 1);

    private final String legacyForm;
    private final String numberCode;
    private final int maxValidityDays;

    WaybillType(String legacyForm, String numberCode, int maxValidityDays) {
        this.legacyForm = legacyForm;
        this.numberCode = numberCode;
        this.maxValidityDays = maxValidityDays;
    }

    public String legacyForm() { return legacyForm; }
    public String numberCode() { return numberCode; }
    public int maxValidityDays() { return maxValidityDays; }

    public boolean isPassenger() {
        return this == WB_BUS || this == WB_TROLLEYBUS || this == WB_MINIBUS || this == WB_PAX_INTL || this == WB_TAXI;
    }
}
