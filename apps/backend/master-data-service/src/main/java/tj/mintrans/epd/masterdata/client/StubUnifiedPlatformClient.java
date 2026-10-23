package tj.mintrans.epd.masterdata.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * DEV-ЗАГЛУШКА единой платформы Минтранса: детерминированно имитирует ответы
 * налоговой (субъект по ИНН) и ГАИ (ТС по госномеру, ВУ) — чтобы сквозной сценарий
 * работал без реальной интеграции. В проде: UNIFIED_PLATFORM_MODE=http.
 *
 * <p>23.10.2026: сроки действия (лицензия, техосмотр, страховка, ВУ, медсправка, допуски
 * ADR) больше НЕ утверждаются как безусловно действующие — заглушка детерминированно
 * имитирует и просроченные случаи ({@link #validOrExpired}/{@link #validOrExpiredMonths}).
 * Заглушка, которая всегда отвечает «действует», ни разу не упражняет блокирующие
 * проверки {@code WaybillService.checkPreflight} — ни в dev, ни по ошибке в проде (если
 * забыли переключить {@code UNIFIED_PLATFORM_MODE=http}, см. {@code ProdStubGuard}).</p>
 */
@Component
@ConditionalOnProperty(name = "epd.unified-platform.mode", havingValue = "stub")
public class StubUnifiedPlatformClient implements UnifiedPlatformClient {

    private static final List<String> LEGAL_NAMES = List.of(
            "ОАО «Нақлиёти автомобилии шаҳри Душанбе»",
            "ҶДММ «Автобус-1»",
            "ҶСК «Троллейбус Хуҷанд»",
            "ҶДММ «Боркашонии Суғд»",
            "МУП «Нақлиёти мусофирбар»");

    private static final List<String> PERSON_NAMES = List.of(
            "Раҳимов Фаррух Саидович",
            "Каримов Дилшод Умарович",
            "Назаров Баҳром Шарифович",
            "Шарипов Манучеҳр Қурбонович",
            "Азизов Сино Файзалиевич");

    private static final List<String> CITIES = List.of(
            "Душанбе", "Хуҷанд", "Бохтар", "Кӯлоб", "Хоруғ");

    /** Марки по типам ТС (1..6). */
    private static final List<List<String>> BRANDS = List.of(
            List.of("Акиа", "ЛиАЗ-5292", "MAN Lion's City"),          // 1 автобус
            List.of("БКМ-321", "Тролза-5265"),                        // 2 троллейбус
            List.of("Mercedes-Benz Sprinter", "ГАЗель Next"),         // 3 микроавтобус
            List.of("Opel Astra", "Toyota Corolla", "Hyundai Sonata"),// 4 легковой
            List.of("HOWO", "КамАЗ-5320", "Isuzu NPR"),               // 5 грузовой
            List.of("Volvo FH", "Mercedes-Benz Actros"));             // 6 грузовой межд.

    @Override
    public Optional<Subject> findSubject(String inn) {
        if (inn == null || !inn.matches("\\d{9,10}")) {
            return Optional.empty();
        }
        int h = Math.abs(inn.hashCode());
        // Тип субъекта детерминированно по последней цифре ИНН: 0-5 юрлицо, 6-7 ИП, 8-9 физлицо
        int last = inn.charAt(inn.length() - 1) - '0';
        String subjectType = last <= 5 ? "LEGAL" : last <= 7 ? "IP" : "PHYSICAL";
        boolean legal = "LEGAL".equals(subjectType);
        String name = legal
                ? LEGAL_NAMES.get(h % LEGAL_NAMES.size())
                : ("IP".equals(subjectType) ? "ИП " : "") + PERSON_NAMES.get(h % PERSON_NAMES.size());
        var today = LocalDate.now();
        return Optional.of(new Subject(
                inn,
                subjectType,
                name,
                (short) (h % 7 + 1),
                CITIES.get(h % CITIES.size()),
                "кӯч. Рӯдакӣ, " + (h % 200 + 1),
                "+992 90 %03d-%02d-%02d".formatted(h % 1000, h % 100, (h / 7) % 100),
                legal ? "info@" + inn + ".tj" : null,
                legal ? PERSON_NAMES.get((h / 3) % PERSON_NAMES.size()) : null,
                today.minusYears(1),
                // Лицензия: НЕ всегда действует (23.10.2026). Заглушка имитирует реальную
                // единую платформу, где часть лицензий просрочена — блокирующие проверки
                // WaybillService.checkPreflight обязаны уметь это отклонять. Раньше здесь
                // стояло безусловное today.plusYears(2) — заглушка утверждала, что лицензия
                // действует ВСЕГДА, то есть ни разу не проверяла реальный путь отказа.
                validOrExpired(today, h, LICENSE_SALT, 2),
                legal ? "ЛП-%06d".formatted(h % 1_000_000) : null, // № лицензии перевозчика (только юрлицо)
                // Дата рождения — только для физлица/ИП (у юрлица нет): детерминированно 21–60 лет назад.
                legal ? null : today.minusYears(21 + h % 40).minusDays(h % 365)));
    }

    // Соли для independent-варьирования разных сроков действия одного и того же хеша
    // (иначе лицензия/техосмотр/страховка одного ТС/субъекта всегда синхронно
    // валидны или просрочены, что не отражает реальность).
    private static final int LICENSE_SALT = 0x5A17;
    private static final int TECH_INSPECTION_SALT = 0x7EC4;
    private static final int CONTROL_CARD_SALT = 0x0CC1;
    private static final int INSURANCE_SALT = 0x1115;
    private static final int ADR_VEHICLE_SALT = 0xADA1;
    private static final int DRIVER_LICENSE_SALT = 0xD71C;
    private static final int MED_CERT_SALT = 0xA1D7;
    private static final int SAFETY_COURSE_SALT = 0x5AFE;
    private static final int ADR_DRIVER_SALT = 0xADA2;

    /**
     * Срок действия: примерно 1 из 5 — уже просрочен (детерминированно по хешу + соли).
     *
     * <p>До 23.10.2026 все подобные поля в заглушке были безусловно действующими
     * (комментарии вида «лицензия действует — блокирующие проверки проходят»). Заглушка
     * не имеет права утверждать это: это решает блокирующие проверки
     * {@code WaybillService.checkPreflight} (лицензия/техосмотр/страховка), и заглушка,
     * которая ВСЕГДА отвечает «действует», никогда не упражняет путь отказа — ни в dev,
     * ни (если забыли переключить режим) по ошибке в проде.</p>
     */
    private static LocalDate validOrExpired(LocalDate today, int h, int salt, int yearsIfValid) {
        return validOrExpiredMonths(today, h, salt, yearsIfValid * 12);
    }

    private static LocalDate validOrExpiredMonths(LocalDate today, int h, int salt, int monthsIfValid) {
        long mixed = Math.abs((long) (h ^ salt) * 2654435761L % 1_000_000L);
        boolean expired = mixed % 5 == 0;
        return expired ? today.minusMonths(1 + mixed % 24) : today.plusMonths(monthsIfValid);
    }

    @Override
    public Optional<VehicleInfo> findVehicle(String registrationNumber) {
        if (registrationNumber == null || registrationNumber.isBlank()) {
            return Optional.empty();
        }
        String reg = registrationNumber.trim().toUpperCase();
        int h = Math.abs(reg.hashCode());
        short transportType = (short) (h % 6 + 1);
        var brands = BRANDS.get(transportType - 1);
        var today = LocalDate.now();
        // Пассажировместимость по типу ТС; для грузовых (5, 6) — null. ВАЖНО: через switch, а не
        // тернарную цепочку int?:null — иначе смешение int/Integer даёт тип int и распаковка
        // null (для грузовых) роняет NPE. Грузовой транспорт обязан синхронизироваться.
        Integer capacity = switch (transportType) {
            case 1 -> 90;
            case 2 -> 100;
            case 3 -> 18;
            case 4 -> 4;
            default -> null;
        };
        return Optional.of(new VehicleInfo(
                reg,
                transportType,
                brands.get(h % brands.size()),
                "TJ%09d".formatted(h % 1_000_000_000),
                (short) (2012 + h % 13),
                capacity,
                transportType >= 5 ? BigDecimal.valueOf(h % 15 + 5) : null,
                // Техосмотр/контрольная карточка/страховка/ADR — не всегда действуют (23.10.2026,
                // см. validOrExpiredMonths): безусловный «всегда действует» не упражняет блокирующие
                // проверки WaybillService.checkPreflight ни разу — ни в dev, ни в проде по ошибке.
                validOrExpiredMonths(today, h, TECH_INSPECTION_SALT, 6),
                validOrExpiredMonths(today, h, CONTROL_CARD_SALT, 12),
                "КК%06d".formatted(h % 1_000_000),
                transportType == 6 ? "INT-%04d-%04d".formatted(today.getYear(), h % 10_000) : null, // сертификат — только межд. грузовые
                validOrExpiredMonths(today, h, INSURANCE_SALT, 9),
                validOrExpired(today, h, ADR_VEHICLE_SALT, 1)));
    }

    @Override
    public Optional<PermitInfo> findPermit(String permitNumber) {
        if (permitNumber == null || permitNumber.isBlank()) {
            return Optional.empty();
        }
        String number = permitNumber.trim().toUpperCase();
        // Имитация E-PERMIT: номера с "BAD" — недействительны, с "EXP" — просрочены
        if (number.contains("BAD")) {
            return Optional.empty();
        }
        var today = LocalDate.now();
        boolean expired = number.contains("EXP");
        int h = Math.abs(number.hashCode());
        return Optional.of(new PermitInfo(
                number,
                !expired,
                expired ? today.minusDays(10) : today.plusMonths(6),
                List.of("Узбекистан", "Казахстан", "Кыргызстан", "Китай", "Иран").get(h % 5)));
    }

    @Override
    public Optional<DriverLicense> findDriverLicense(String inn) {
        if (inn == null || !inn.matches("\\d{9,10}")) {
            return Optional.empty();
        }
        int h = Math.abs(inn.hashCode());
        var today = LocalDate.now();
        return Optional.of(new DriverLicense(
                "AB%07d".formatted(h % 10_000_000),
                h % 2 == 0 ? "B, C, D" : "B, D",
                // ВУ/медсправка/курс БДД/ADR — не всегда действуют (23.10.2026, см.
                // validOrExpired выше): безусловный «всегда действует» не упражняет блокирующие
                // проверки WaybillService.checkPreflight ни разу — ни в dev, ни в проде по ошибке.
                validOrExpired(today, h, DRIVER_LICENSE_SALT, 3),
                "MC%06d".formatted(h % 1_000_000),
                validOrExpired(today, h, MED_CERT_SALT, 1),
                "БДД-%06d".formatted(h % 1_000_000),
                validOrExpired(today, h, SAFETY_COURSE_SALT, 2),
                validOrExpired(today, h, ADR_DRIVER_SALT, 1),
                (short) (h % 30 + 1))); // общий стаж вождения, лет (1..30)
    }
}
