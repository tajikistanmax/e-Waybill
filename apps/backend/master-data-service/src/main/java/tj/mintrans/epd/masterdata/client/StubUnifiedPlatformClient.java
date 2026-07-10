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
 */
@Component
@ConditionalOnProperty(name = "epd.unified-platform.mode", havingValue = "stub", matchIfMissing = true)
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
                today.plusYears(2))); // лицензия действует — блокирующие проверки проходят
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
        return Optional.of(new VehicleInfo(
                reg,
                transportType,
                brands.get(h % brands.size()),
                "TJ%09d".formatted(h % 1_000_000_000),
                (short) (2012 + h % 13),
                transportType == 1 ? 90 : transportType == 2 ? 100 : transportType == 3 ? 18 : transportType == 4 ? 4 : null,
                transportType >= 5 ? BigDecimal.valueOf(h % 15 + 5) : null,
                today.plusMonths(6),   // техосмотр действует
                today.plusMonths(12))); // контрольная карточка действует
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
                today.plusYears(3),
                "MC%06d".formatted(h % 1_000_000),
                today.plusYears(1)));
    }
}
