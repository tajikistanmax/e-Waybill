package tj.mintrans.epd.waybill.domain;

/**
 * Типы путевых листов (spec/data/waybill-types.yaml) с legacy-маппингом и кодом для национального номера.
 */
public enum WaybillType {
    WB_CAR("3-С", "01", 7, 2, -1, 650),
    WB_TAXI("3-С такси", "02", 7, 2, -1, 650),
    WB_MINIBUS("1-А", "03", 4, 2, -1, 650),
    WB_BUS("Т(1-АД)", "04", 1, 2, 5, 0),
    WB_TROLLEYBUS("Т(1-АД) троллейбус", "05", 1, 2, 5, 0),
    WB_TRUCK("2-Б", "06", 15, 1, -1, 0),
    WB_TRUCK_INTL("5Б-БМ", "07", 30, 1, -1, 0),
    WB_PAX_INTL("4М-БМ", "08", 30, 2, -1, 0),
    WB_SPECIAL("спецтехника", "09", 7, 1, -1, 0),
    WB_DANGEROUS("опасные грузы", "10", 1, 1, -1, 0);

    private final String legacyForm;
    private final String numberCode;
    private final int maxValidityDays;
    private final int maxFuelTypes;
    private final int maxAdditionalFuelLiters;
    private final int maxDailyKm;

    WaybillType(String legacyForm, String numberCode, int maxValidityDays, int maxFuelTypes,
                int maxAdditionalFuelLiters, int maxDailyKm) {
        this.legacyForm = legacyForm;
        this.numberCode = numberCode;
        this.maxValidityDays = maxValidityDays;
        this.maxFuelTypes = maxFuelTypes;
        this.maxAdditionalFuelLiters = maxAdditionalFuelLiters;
        this.maxDailyKm = maxDailyKm;
    }

    /**
     * Лимит суточного пробега, км (MIGRATION.md 12.5, legacy {@code max_counter_value} = 650 для 3-С и 1-А);
     * 0 — без лимита. Применяется только при {@code epd.limits.daily-km-enabled=true}: в legacy это скрытое поле
     * формы и тексты сообщений без действующих правил (фактически не проверялось) — по умолчанию выключено.
     */
    public int maxDailyKm() { return maxDailyKm; }

    public String legacyForm() { return legacyForm; }
    public String numberCode() { return numberCode; }
    public int maxValidityDays() { return maxValidityDays; }

    /**
     * Лимит видов топлива на ПЛ (MIGRATION.md 12.4, legacy {@code kvd/*Request: fuels … max:N}):
     * грузовые формы 2-Б / 5Б-БМ (и производные спец/опасные) — 1, пассажирские 1-АД / 1-А / 3-С — 2.
     */
    public int maxFuelTypes() { return maxFuelTypes; }

    /**
     * Лимит довыдачи в пути «Харҷи иловагӣ», л (MIGRATION.md 12.8): для 1-АД (автобус/троллейбус) — 0…5
     * (legacy {@code Waybill1adRequest: additional_value}, {@code kvd/StoreWaybill1adRequest: between:0,5});
     * −1 — без лимита (остальные формы: только {@code min:0}).
     */
    public int maxAdditionalFuelLiters() { return maxAdditionalFuelLiters; }

    public boolean isPassenger() {
        return this == WB_BUS || this == WB_TROLLEYBUS || this == WB_MINIBUS || this == WB_PAX_INTL || this == WB_TAXI;
    }
}
