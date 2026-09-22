package tj.mintrans.epd.waybill.service;

import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Правила B2B-канала перевозчиков {@code ref/*} (MIGRATION.md 9.8 / 9.2 — legacy {@code Requests/kvd/StoreWaybill*Request},
 * {@code Update*Request}): формы, обязательные поля и лимиты с текстами сообщений оригинала; сборка {@code typeData}
 * e-Waybill из snake_case полей legacy-запроса.
 */
public final class RefChannelRules {

    /** Форма legacy-канала → вид(ы) ПЛ e-Waybill; код — как в {@code waybill/confirm} (waybill_type 1..6). */
    public enum Form {
        WAYBILL1AD("waybill1ad", 1, WaybillType.WB_BUS),
        WAYBILL1ADE("waybill1ade", 2, WaybillType.WB_TROLLEYBUS),
        WAYBILL1A("waybill1a", 3, WaybillType.WB_MINIBUS),
        WAYBILL3C("waybill3c", 4, WaybillType.WB_CAR, WaybillType.WB_TAXI),
        WAYBILL2B("waybill2b", 5, WaybillType.WB_TRUCK, WaybillType.WB_DANGEROUS),
        WAYBILL5BBM("waybill5bbm", 6, WaybillType.WB_TRUCK_INTL);

        private final String key;
        private final int legacyCode;
        private final Set<WaybillType> types;

        Form(String key, int legacyCode, WaybillType... types) {
            this.key = key;
            this.legacyCode = legacyCode;
            this.types = Set.of(types);
        }

        public String key() { return key; }

        public int legacyCode() { return legacyCode; }

        public Set<WaybillType> types() { return types; }

        /** Вид ПЛ при создании через канал. */
        public WaybillType primary() {
            return switch (this) {
                case WAYBILL1AD -> WaybillType.WB_BUS;
                case WAYBILL1ADE -> WaybillType.WB_TROLLEYBUS;
                case WAYBILL1A -> WaybillType.WB_MINIBUS;
                case WAYBILL3C -> WaybillType.WB_CAR;
                case WAYBILL2B -> WaybillType.WB_TRUCK;
                case WAYBILL5BBM -> WaybillType.WB_TRUCK_INTL;
            };
        }

        public boolean isPassengerDaily() {
            return this == WAYBILL1AD || this == WAYBILL1ADE;
        }

        public static Form parse(String key) {
            if (key == null) {
                return null;
            }
            String k = key.trim().toLowerCase(Locale.ROOT);
            for (Form f : values()) {
                if (f.key.equals(k)) {
                    return f;
                }
            }
            return null;
        }

        public static Form byCode(Integer code) {
            if (code == null) {
                return null;
            }
            for (Form f : values()) {
                if (f.legacyCode == code) {
                    return f;
                }
            }
            return null;
        }

        public static Form ofType(WaybillType type) {
            for (Form f : values()) {
                if (f.types.contains(type)) {
                    return f;
                }
            }
            return null;
        }
    }

    static final String MSG_ORG = "Параметр organization_rma обязателен.";
    static final String MSG_TRANSPORT = "Параметр transport_registration_number обязателен.";
    static final String MSG_DRIVER = "Параметр driver_rma обязателен.";
    static final String MSG_FIRST_DRIVER = "Параметр first_driver_rma обязателен.";
    static final String MSG_EMPLOYEE = "Параметр employee_rma обязателен.";
    static final String MSG_BEGIN_PATH = "Гашти ибтидои должен быть begin_path_a или begin_path_b.";
    static final String MSG_FUEL_ID = "Тип топлива должен быть 1, 2 или 3.";
    static final String MSG_ADDITIONAL = "Харҷи иловагӣ должен быть между 0 и 5.";
    static final String MSG_TYPE_SERVICE = "Тип услуги должен быть 1, 2 или 3.";
    static final String MSG_SHIPMENT = "Намуди ҳамлу нақл должен быть 1 или 2.";
    static final String MSG_BBA = "Поле bba_number обязательно.";

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DT_S = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DT_M = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private RefChannelRules() {
    }

    // ------------------------------------------------------------------ доступ к полям

    public static String str(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    public static boolean has(Map<String, Object> m, String key) {
        return !str(m, key).isEmpty();
    }

    public static BigDecimal num(Map<String, Object> m, String key) {
        String s = str(m, key);
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s.replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new UnprocessableException("Параметр %s должен быть числом.".formatted(key));
        }
    }

    public static Integer intVal(Map<String, Object> m, String key) {
        BigDecimal v = num(m, key);
        return v == null ? null : v.intValue();
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> listOfMaps(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> list)) {
            throw new UnprocessableException("Параметр %s должен быть массивом.".formatted(key));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> mm)) {
                throw new UnprocessableException("Параметр %s: элементы должны быть объектами.".formatted(key));
            }
            out.add((Map<String, Object>) mm);
        }
        return out;
    }

    public static List<String> listOfStrings(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> list)) {
            throw new UnprocessableException("Параметр %s должен быть массивом.".formatted(key));
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            out.add(String.valueOf(o).trim());
        }
        return out;
    }

    /** {@code H:i} → LocalTime; пусто → null; иное → 422. */
    public static LocalTime time(Map<String, Object> m, String key) {
        String s = str(m, key);
        if (s.isEmpty()) {
            return null;
        }
        try {
            return LocalTime.parse(s.length() > 5 ? s.substring(0, 5) : s, HM);
        } catch (DateTimeParseException e) {
            throw new UnprocessableException("Параметр %s: ожидается время в формате H:i.".formatted(key));
        }
    }

    public static LocalDate date(Map<String, Object> m, String key) {
        String s = str(m, key);
        if (s.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (DateTimeParseException e) {
            throw new UnprocessableException("Параметр %s: ожидается дата в формате Y-m-d.".formatted(key));
        }
    }

    /** {@code Y-m-d H:i[:s]} или {@code Y-m-dTH:i[:s]}; пусто → null. */
    public static LocalDateTime dateTime(Map<String, Object> m, String key) {
        String s = str(m, key).replace('T', ' ');
        if (s.isEmpty()) {
            return null;
        }
        try {
            return s.length() >= 19 ? LocalDateTime.parse(s.substring(0, 19), DT_S) : LocalDateTime.parse(s, DT_M);
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(s.substring(0, Math.min(10, s.length()))).atStartOfDay();
            } catch (DateTimeParseException e2) {
                throw new UnprocessableException("Параметр %s: ожидается дата-время в формате Y-m-d H:i:s.".formatted(key));
            }
        }
    }

    // ------------------------------------------------------------------ валидация

    /** Проверка тела {@code store} по правилам legacy {@code StoreWaybill*Request}. */
    public static void validateStore(Form form, Map<String, Object> body) {
        requireRma(body, "organization_rma", MSG_ORG);
        require(body, "transport_registration_number", MSG_TRANSPORT);
        if (form == Form.WAYBILL5BBM) {
            require(body, "first_driver_rma", MSG_FIRST_DRIVER);
        } else {
            require(body, "driver_rma", MSG_DRIVER);
        }
        validateCommon(form, body, true);
    }

    /** Проверка тела {@code update} (legacy {@code Update*Request}): те же правила, обязательны только org/ТС. */
    public static void validateUpdate(Form form, Map<String, Object> body) {
        validateCommon(form, body, false);
    }

    private static void validateCommon(Form form, Map<String, Object> body, boolean store) {
        switch (form) {
            case WAYBILL1AD, WAYBILL1ADE -> {
                if (store) {
                    require(body, "employee_rma", MSG_EMPLOYEE);
                }
                if (form == Form.WAYBILL1ADE || !store) {
                    require(body, "schedule", "Параметр schedule обязателен.");
                }
                if (form == Form.WAYBILL1AD) {
                    if (store) {
                        require(body, "exit_date", "Параметр exit_date обязателен (H:i).");
                    }
                    time(body, "exit_date");
                }
                beginPath(body);
                nonNegative(body, "number_lap", "Миқдори гардиш не может быть отрицательным.");
                nonNegative(body, "earning", "Маблағ не может быть отрицательным.");
                nonNegative(body, "indication_counter_entry", "Нишондоди суръатнигор не может быть отрицательным.");
                time(body, "work_time");
                time(body, "conditioner_time");
                time(body, "entry_date");
                time(body, "client_time");
                validateFuels(listOfMaps(body, "fuels"), 2, "fuels", form == Form.WAYBILL1AD);
            }
            case WAYBILL1A -> {
                if (store) {
                    require(body, "route_id", "Параметр route_id обязателен.");
                    require(body, "schedule", "Параметр schedule обязателен.");
                }
                nonNegative(body, "kassa", "Параметр kassa не может быть отрицательным.");
                nonNegative(body, "indication_counter_entry", "Нишондоди суръатнигор не может быть отрицательным.");
                dateTime(body, "entry_date");
                List<Map<String, Object>> days = listOfMaps(body, "work_days");
                if (days.size() > 4) {
                    throw new UnprocessableException("Максимальное количество рабочих дней - 4.");
                }
                for (Map<String, Object> d : days) {
                    require(d, "date", "work_days.*.date обязателен.");
                    require(d, "exit_time", "work_days.*.exit_time обязателен (H:i).");
                    require(d, "entry_time", "work_days.*.entry_time обязателен (H:i).");
                    require(d, "begin_path_a", "work_days.*.begin_path_a обязателен.");
                    validateDay(d, true);
                    validateFuels(listOfMaps(d, "fuels"), 2, "work_days.*.fuels", false);
                }
            }
            case WAYBILL3C -> {
                Integer ts = intVal(body, "type_service");
                if (store || has(body, "type_service")) {
                    if (ts == null || ts < 1 || ts > 3) {
                        throw new UnprocessableException(MSG_TYPE_SERVICE);
                    }
                }
                if (ts != null && ts == 2) {
                    if (store && !has(body, "route_id")) {
                        throw new UnprocessableException("Поле route_id обязательно при type_service = 2.");
                    }
                    if (store && !has(body, "schedule")) {
                        throw new UnprocessableException("Поле schedule обязательно при type_service = 2.");
                    }
                }
                for (String r : listOfStrings(body, "regions_id")) {
                    if (!r.matches("[1-7]")) {
                        throw new UnprocessableException("regions_id: допустимые значения 1–7.");
                    }
                }
                nonNegative(body, "kassa", "Параметр kassa не может быть отрицательным.");
                nonNegative(body, "indication_counter_entry", "Нишондоди суръатнигор не может быть отрицательным.");
                dateTime(body, "entry_date");
                for (Map<String, Object> d : listOfMaps(body, "work_days")) {
                    if (ts != null && ts == 2) {
                        if (!has(d, "begin_path_a")) {
                            throw new UnprocessableException("Поле begin_path_a обязательно при type_service = 2.");
                        }
                        if (!has(d, "begin_path_b")) {
                            throw new UnprocessableException("Поле begin_path_b обязательно при type_service = 2.");
                        }
                    }
                    validateDay(d, false);
                    validateFuels(listOfMaps(d, "fuels"), 2, "work_days.*.fuels", false);
                }
            }
            case WAYBILL2B -> {
                Integer sh = intVal(body, "type_of_shipment");
                if (store || has(body, "type_of_shipment")) {
                    if (sh == null || sh < 1 || sh > 2) {
                        throw new UnprocessableException(MSG_SHIPMENT);
                    }
                }
                if (store) {
                    require(body, "direction_id", "Параметр direction_id обязателен.");
                    require(body, "client_id", "Параметр client_id обязателен.");
                }
                nonNegative(body, "indication_counter_entry", "Нишондоди суръатнигор не может быть отрицательным.");
                List<Map<String, Object>> trailers = listOfMaps(body, "trailers");
                if (trailers.size() > 2) {
                    throw new UnprocessableException("Максимальное количество прицепов - 2.");
                }
                for (Map<String, Object> t : trailers) {
                    require(t, "registration_number", "trailers.*.registration_number обязателен.");
                    require(t, "brand", "trailers.*.brand обязателен.");
                }
                List<Map<String, Object>> days = listOfMaps(body, "work_days");
                if (body.get("work_days") != null && days.isEmpty()) {
                    throw new UnprocessableException("Минимальное количество рабочих дней - 1.");
                }
                if (days.size() > 15) {
                    throw new UnprocessableException("Максимальное количество рабочих дней - 15.");
                }
                for (Map<String, Object> d : days) {
                    require(d, "date", "work_days.*.date обязателен.");
                    require(d, "exit_time", "work_days.*.exit_time обязателен (H:i).");
                    require(d, "entry_time", "work_days.*.entry_time обязателен (H:i).");
                    validateDay(d, true);
                    validateFuels(listOfMaps(d, "fuels"), 1, "work_days.*.fuels", false);
                }
            }
            case WAYBILL5BBM -> {
                if (store) {
                    require(body, "load_country_name", "Параметр load_country_name обязателен.");
                    require(body, "load_city_name", "Параметр load_city_name обязателен.");
                    require(body, "unload_country_name", "Параметр unload_country_name обязателен.");
                    require(body, "unload_city_name", "Параметр unload_city_name обязателен.");
                    require(body, "cargo_id", "Параметр cargo_id обязателен.");
                    require(body, "bba_number", MSG_BBA);
                    // Требования e-Waybill (12.10 / E-PERMIT): виза и дозвол обязательны — в legacy visa_* были nullable.
                    require(body, "visa_country_name", "Параметр visa_country_name обязателен (e-Waybill).");
                    require(body, "visa_expire_date", "Параметр visa_expire_date обязателен (e-Waybill).");
                    require(body, "permit_number", "Параметр permit_number обязателен (дозвол E-PERMIT, e-Waybill).");
                }
                date(body, "visa_expire_date");
                dateTime(body, "arrival_time");
                dateTime(body, "entry_date");
                nonNegative(body, "cargo_capacity", "Параметр cargo_capacity не может быть отрицательным.");
                nonNegative(body, "cargo_distance", "Параметр cargo_distance не может быть отрицательным.");
                nonNegative(body, "indication_counter_entry", "Нишондоди суръатнигор не может быть отрицательным.");
                listOfStrings(body, "transit_countries_name");
                validateFuels(listOfMaps(body, "fuels"), 1, "fuels", false);
            }
        }
    }

    private static void validateDay(Map<String, Object> d, boolean countersRequired) {
        date(d, "date");
        time(d, "exit_time");
        time(d, "entry_time");
        time(d, "client_time");
        time(d, "conditioner_time");
        time(d, "work_time");
        beginPath(d);
        nonNegative(d, "laps", "work_days.*.laps не может быть отрицательным.");
        nonNegative(d, "indication_counter_exit", "Нишондоди суръатнигор не может быть отрицательным.");
        nonNegative(d, "indication_counter_entry", "Нишондоди суръатнигор не может быть отрицательным.");
        if (countersRequired) {
            require(d, "indication_counter_exit", "work_days.*.indication_counter_exit обязателен.");
            require(d, "indication_counter_entry", "work_days.*.indication_counter_entry обязателен.");
        }
    }

    private static void validateFuels(List<Map<String, Object>> fuels, int max, String field, boolean additionalCapped) {
        if (fuels.size() > max) {
            throw new UnprocessableException("Максимальное количество видов топлива - %d.".formatted(max));
        }
        for (Map<String, Object> f : fuels) {
            Integer id = intVal(f, "fuel_id");
            if (id == null || id < 1 || id > 3) {
                throw new UnprocessableException(MSG_FUEL_ID);
            }
            BigDecimal given = num(f, "fuel_given");
            if (given == null || given.signum() < 0) {
                throw new UnprocessableException(field + ".*.fuel_given обязателен и не может быть отрицательным.");
            }
            BigDecimal remain = num(f, "remain_fuel_before_exit");
            if (remain == null || remain.signum() < 0) {
                throw new UnprocessableException(field + ".*.remain_fuel_before_exit обязателен и не может быть отрицательным.");
            }
            BigDecimal additional = num(f, "additional");
            if (additional != null && (additional.signum() < 0 || (additionalCapped && additional.compareTo(new BigDecimal("5")) > 0))) {
                throw new UnprocessableException(additionalCapped ? MSG_ADDITIONAL : field + ".*.additional не может быть отрицательным.");
            }
            nonNegative(f, "be_given", field + ".*.be_given не может быть отрицательным.");
            nonNegative(f, "coef_below_0", field + ".*.coef_below_0 не может быть отрицательным.");
        }
    }

    private static void beginPath(Map<String, Object> m) {
        for (String key : new String[]{"begin_path_a", "begin_path_b"}) {
            String v = str(m, key);
            if (!v.isEmpty() && !v.equals("begin_path_a") && !v.equals("begin_path_b")) {
                throw new UnprocessableException(MSG_BEGIN_PATH);
            }
        }
    }

    private static void require(Map<String, Object> m, String key, String message) {
        if (!has(m, key)) {
            throw new UnprocessableException(message);
        }
    }

    private static void requireRma(Map<String, Object> m, String key, String message) {
        String v = str(m, key);
        if (!v.matches("\\d{9,10}")) {
            throw new UnprocessableException(message);
        }
    }

    private static void nonNegative(Map<String, Object> m, String key, String message) {
        BigDecimal v = num(m, key);
        if (v != null && v.signum() < 0) {
            throw new UnprocessableException(message);
        }
    }

    // ------------------------------------------------------------------ typeData

    /** Вид услуги 3-С: legacy {@code type_service} 1/2/3 → {@code serviceKind} TAXI/ROUTE/HOURLY. */
    public static String serviceKind(Integer typeService) {
        if (typeService == null) {
            return null;
        }
        return switch (typeService) {
            case 1 -> "TAXI";
            case 2 -> "ROUTE";
            case 3 -> "HOURLY";
            default -> null;
        };
    }

    /** Вид перевозки 2-Б: legacy {@code type_of_shipment} 1/2 → {@code shipmentKind} PIECEWORK/HOURLY. */
    public static String shipmentKind(Integer typeOfShipment) {
        if (typeOfShipment == null) {
            return null;
        }
        return switch (typeOfShipment) {
            case 1 -> "PIECEWORK";
            case 2 -> "HOURLY";
            default -> null;
        };
    }

    /** Часы из {@code H:i} (например «01:30» → 1.5). */
    public static BigDecimal hours(LocalTime t) {
        if (t == null) {
            return null;
        }
        return BigDecimal.valueOf(t.getHour()).add(BigDecimal.valueOf(t.getMinute()).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP));
    }

    /**
     * Поля формы legacy → {@code typeData} e-Waybill (только присутствующие в теле ключи — для {@code update}
     * это слияние с текущими данными).
     */
    public static Map<String, Object> typeData(Form form, Map<String, Object> body) {
        Map<String, Object> td = new LinkedHashMap<>();
        switch (form) {
            case WAYBILL1AD, WAYBILL1ADE -> {
                put(td, "beginPathA", str(body, "begin_path_a"));
                put(td, "beginPathB", str(body, "begin_path_b"));
                put(td, "numberLap", intVal(body, "number_lap"));
                put(td, "workTime", str(body, "work_time"));
                put(td, "conditionerTime", str(body, "conditioner_time"));
                LocalTime cond = time(body, "conditioner_time");
                if (cond != null) {
                    td.put("conditionerHours", hours(cond));
                }
                put(td, "earning", num(body, "earning"));
                put(td, "clientId", str(body, "client_id"));
                put(td, "clientTime", str(body, "client_time"));
                put(td, "exitTime", str(body, "exit_date"));
                put(td, "entryTime", str(body, "entry_date"));
            }
            case WAYBILL1A -> {
                put(td, "kassa", num(body, "kassa"));
                put(td, "entryDate", str(body, "entry_date"));
            }
            case WAYBILL3C -> {
                Integer ts = intVal(body, "type_service");
                if (ts != null) {
                    td.put("serviceKind", serviceKind(ts));
                    td.put("typeService", String.valueOf(ts));
                }
                if (body.get("regions_id") != null) {
                    List<Integer> regions = new ArrayList<>();
                    for (String r : listOfStrings(body, "regions_id")) {
                        regions.add(Integer.parseInt(r));
                    }
                    td.put("workRegions", regions);
                }
                put(td, "kassa", num(body, "kassa"));
                put(td, "entryDate", str(body, "entry_date"));
            }
            case WAYBILL2B -> {
                Integer sh = intVal(body, "type_of_shipment");
                if (sh != null) {
                    td.put("shipmentKind", shipmentKind(sh));
                }
                put(td, "directionId", str(body, "direction_id"));
                put(td, "clientId", str(body, "client_id"));
                put(td, "clientName", str(body, "client_name"));
                if (body.get("trailers") != null) {
                    List<Map<String, Object>> trailers = new ArrayList<>();
                    for (Map<String, Object> t : listOfMaps(body, "trailers")) {
                        Map<String, Object> tr = new LinkedHashMap<>();
                        tr.put("registrationNumber", str(t, "registration_number").toUpperCase());
                        tr.put("brand", str(t, "brand"));
                        put(tr, "carrying", num(t, "carrying"));
                        put(tr, "weight", num(t, "weight"));
                        trailers.add(tr);
                    }
                    td.put("trailers", trailers);
                }
                put(td, "entryDate", str(body, "entry_date"));
            }
            case WAYBILL5BBM -> {
                put(td, "visaCountry", str(body, "visa_country_name"));
                put(td, "visaValidTo", str(body, "visa_expire_date"));
                put(td, "loadCountry", str(body, "load_country_name"));
                put(td, "loadCity", str(body, "load_city_name"));
                put(td, "unloadCountry", str(body, "unload_country_name"));
                put(td, "unloadCity", str(body, "unload_city_name"));
                if (body.get("transit_countries_name") != null) {
                    td.put("transitCountries", listOfStrings(body, "transit_countries_name"));
                }
                put(td, "permitNumber", str(body, "permit_number"));
                put(td, "cargoId", str(body, "cargo_id"));
                put(td, "cargoName", str(body, "cargo_name"));
                put(td, "bbaNumber", str(body, "bba_number"));
                put(td, "cargoCapacity", num(body, "cargo_capacity"));
                put(td, "cargoDistance", num(body, "cargo_distance"));
                put(td, "arrivalTime", str(body, "arrival_time").replace(' ', 'T'));
                put(td, "clientId", str(body, "client_id"));
                put(td, "entryDate", str(body, "entry_date"));
            }
        }
        return td;
    }

    private static void put(Map<String, Object> td, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String s && s.isEmpty()) {
            return;
        }
        td.put(key, value);
    }
}
