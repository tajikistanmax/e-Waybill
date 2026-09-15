package tj.mintrans.epd.waybill.calc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tj.mintrans.epd.waybill.calc.model.CoefficientBreakdown;
import tj.mintrans.epd.waybill.calc.model.DirectionCoefRef;
import tj.mintrans.epd.waybill.calc.model.DriveClassRef;
import tj.mintrans.epd.waybill.calc.model.RouteCoefRef;
import tj.mintrans.epd.waybill.calc.model.SimpleCoefRef;
import tj.mintrans.epd.waybill.calc.model.UsedCoefRef;
import tj.mintrans.epd.waybill.calc.model.WinterCoefRef;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Подбор коэффициентов расхода топлива: зимний, горный, городской, износа, класса водителя.
 *
 * <p>Дословный перенос движка «Роҳхат» ({@code tj.etrans.rohkhat.calc.CoefficientService}).
 * Эталон: docs/spec/07-calculations.md §1.4 ({@code helpers.php:137, getCoef()}),
 * §3.4 ({@code CargoFuelBase.php:147, getCoef()}), §10 (сводная таблица констант).</p>
 *
 * <p>Пассажирская и грузовая ветки математически РАЗНЫЕ и намеренно не объединены:
 * в пассажирской участвуют {@code station_coef} и {@code road_quality}, в грузовой — нет.</p>
 *
 * <p>Справочники ({@link CoefficientDictionaries}) читаются из master-data через
 * {@link HttpCoefficientDictionaries}; в тестах подставляется mock.</p>
 */
@Service
public class CoefficientCalculator {

    private static final Logger log = LoggerFactory.getLogger(CoefficientCalculator.class);

    /** Возраст ТС (лет), выше которого действует максимальная надбавка износа. */
    static final int FALLBACK_USED_AGE_HIGH = 8;
    /** Пробег (км), выше которого действует максимальная надбавка износа. */
    static final int FALLBACK_USED_KM_HIGH = 150_000;
    /** Максимальная надбавка износа, %. */
    static final int FALLBACK_USED_COEF_HIGH = 10;
    /** Возраст ТС (лет) для средней надбавки износа. */
    static final int FALLBACK_USED_AGE_LOW = 5;
    /** Пробег (км) для средней надбавки износа. */
    static final int FALLBACK_USED_KM_LOW = 100_000;
    /** Средняя надбавка износа, %. */
    static final int FALLBACK_USED_COEF_LOW = 5;

    private final CoefficientDictionaries dict;

    public CoefficientCalculator(CoefficientDictionaries dict) {
        this.dict = dict;
    }

    // ------------------------------------------------------------------ зимний

    /**
     * Зимний коэффициент по дате.
     *
     * @param winterCoefId идентификатор записи {@code fuel_winter_coef} ({@code null} — не задан)
     * @param date         дата, на которую определяется зимний период
     * @return значение коэффициента в процентах, 0 если период не действует или записи нет
     */
    public int winterCoef(Long winterCoefId, LocalDate date) {
        if (winterCoefId == null) {
            return 0;
        }
        if (date == null) {
            log.warn("Зимний коэффициент {}: дата не задана, коэффициент не применён", winterCoefId);
            return 0;
        }
        Optional<WinterCoefRef> found = dict.winterCoef(winterCoefId);
        if (found.isEmpty()) {
            log.warn("Зимний коэффициент id={} не найден в fuel_winter_coef, применено значение 0", winterCoefId);
            return 0;
        }
        WinterCoefRef coef = found.get();
        if (coef.periodFrom() == null || coef.periodTo() == null) {
            log.warn("Зимний коэффициент id={}: период не заполнен, применено значение 0", winterCoefId);
            return 0;
        }
        if (!isWinterPeriod(date, coef.periodFrom(), coef.periodTo())) {
            return 0;
        }
        if (coef.coef() == null) {
            log.warn("Зимний коэффициент id={}: поле coef пусто, применено значение 0", winterCoefId);
            return 0;
        }
        return coef.coef();
    }

    /**
     * Проверка попадания даты в зимний период (сравнение по MMDD, год игнорируется).
     * Границы ВКЛЮЧИТЕЛЬНО; переход через новый год обрабатывается явно.
     *
     * @param date дата проверки
     * @param from начало периода; учитываются месяц и день
     * @param to   конец периода; учитываются месяц и день
     * @return {@code true}, если дата попадает в период
     */
    public static boolean isWinterPeriod(LocalDate date, LocalDate from, LocalDate to) {
        if (date == null || from == null || to == null) {
            return false;
        }
        int value = monthDay(date);
        int start = monthDay(from);
        int end = monthDay(to);
        if (start <= end) {
            return value >= start && value <= end;
        }
        return value >= start || value <= end;
    }

    /**
     * Дословное условие зимнего периода из оригинала ({@code helpers.php:153} —
     * {@code valid > from || valid < to}), для сверки исторических отчётов.
     *
     * @param date дата проверки
     * @param from начало периода
     * @param to   конец периода
     * @return {@code true}, если сработало условие оригинала
     */
    public static boolean isWinterPeriodLegacy(LocalDate date, LocalDate from, LocalDate to) {
        if (date == null || from == null || to == null) {
            return false;
        }
        int value = monthDay(date);
        return value > monthDay(from) || value < monthDay(to);
    }

    private static int monthDay(LocalDate date) {
        return date.getMonthValue() * 100 + date.getDayOfMonth();
    }

    // ------------------------------------------------------------ горный/городской

    /**
     * Горный коэффициент по ключу справочника {@code mountain_coef}.
     *
     * @param mountainCoefId ключ записи справочника
     * @return значение коэффициента в процентах, 0 при отсутствии
     */
    public int mountainCoef(Long mountainCoefId) {
        if (mountainCoefId == null) {
            return 0;
        }
        Optional<SimpleCoefRef> found = dict.mountainCoef(mountainCoefId);
        if (found.isEmpty()) {
            log.warn("Горный коэффициент id={} не найден в mountain_coef, применено значение 0", mountainCoefId);
            return 0;
        }
        Integer coef = found.get().coef();
        if (coef == null) {
            log.warn("Горный коэффициент id={}: поле coef пусто, применено значение 0", mountainCoefId);
            return 0;
        }
        return coef;
    }

    /**
     * Городской коэффициент по ключу справочника {@code city_coef}.
     *
     * @param cityCoefId ключ записи справочника
     * @return значение коэффициента в процентах, 0 при отсутствии
     */
    public int cityCoef(Long cityCoefId) {
        if (cityCoefId == null) {
            return 0;
        }
        Optional<SimpleCoefRef> found = dict.cityCoef(cityCoefId);
        if (found.isEmpty()) {
            log.warn("Городской коэффициент id={} не найден в city_coef, применено значение 0", cityCoefId);
            return 0;
        }
        Integer coef = found.get().coef();
        if (coef == null) {
            log.warn("Городской коэффициент id={}: поле coef пусто, применено значение 0", cityCoefId);
            return 0;
        }
        return coef;
    }

    // ------------------------------------------------------------------- износ

    /**
     * Коэффициент износа ТС по возрасту и пробегу на выезде.
     *
     * <p>Пороги берутся из справочника {@code used_coef} (максимальный подходящий);
     * при пустом справочнике применяются исходные константы оригинала
     * (8 лет/150000 км → 10 %, 5 лет/100000 км → 5 %).</p>
     *
     * @param yearManufacture       год выпуска ({@code null} → 0)
     * @param indicationCounterExit показание одометра на выезде ({@code null} → 0 км)
     * @param onDate                дата расчёта ({@code null} — текущая дата)
     * @return надбавка износа в процентах
     */
    public int usedCoef(LocalDate yearManufacture, Long indicationCounterExit, LocalDate onDate) {
        if (yearManufacture == null) {
            log.warn("Коэффициент износа: год выпуска ТС не заполнен, применено значение 0");
            return 0;
        }
        int currentYear = (onDate == null ? LocalDate.now() : onDate).getYear();
        int age = currentYear - yearManufacture.getYear();
        long km = indicationCounterExit == null ? 0L : indicationCounterExit;

        List<UsedCoefRef> rows = dict.usedCoefRows();
        if (rows == null || rows.isEmpty()) {
            log.warn("Справочник used_coef пуст, применены пороги оригинала (8 лет/150000 км и 5 лет/100000 км)");
            return fallbackUsedCoef(age, km);
        }

        int result = 0;
        for (UsedCoefRef row : rows) {
            int rowYear = row.year() == null ? 0 : row.year();
            long rowKm = row.km() == null ? 0L : row.km();
            int rowCoef = row.coef() == null ? 0 : row.coef();
            if (age > rowYear && km > rowKm && rowCoef > result) {
                result = rowCoef;
            }
        }
        return result;
    }

    /**
     * Пороги износа, зашитые в оригинале ({@code helpers.php:140}).
     *
     * @param age возраст ТС, лет
     * @param km  пробег на выезде, км
     * @return надбавка износа в процентах
     */
    static int fallbackUsedCoef(int age, long km) {
        if (age > FALLBACK_USED_AGE_HIGH && km > FALLBACK_USED_KM_HIGH) {
            return FALLBACK_USED_COEF_HIGH;
        }
        if (age > FALLBACK_USED_AGE_LOW && km > FALLBACK_USED_KM_LOW) {
            return FALLBACK_USED_COEF_LOW;
        }
        return 0;
    }

    // ---------------------------------------------------------- класс водителя

    /**
     * Коэффициент класса водителя по справочнику {@code drive_classes}.
     *
     * <p>РАСХОЖДЕНИЕ С ОРИГИНАЛОМ: в исходной системе {@code drive_classes} в расчётах не
     * участвует (только CRUD). Метод — основа для будущего расчёта, по умолчанию 0.</p>
     *
     * @param driveClass наименование класса
     * @return значение коэффициента в процентах, 0 при отсутствии
     */
    public int driveClassCoef(String driveClass) {
        if (driveClass == null || driveClass.isBlank()) {
            return 0;
        }
        List<DriveClassRef> rows = dict.driveClasses();
        if (rows == null || rows.isEmpty()) {
            log.warn("Справочник drive_classes пуст, коэффициент класса водителя = 0");
            return 0;
        }
        for (DriveClassRef row : rows) {
            if (driveClass.equalsIgnoreCase(row.driveClass())) {
                Integer coef = row.coef();
                if (coef == null) {
                    log.warn("Класс водителя '{}': поле coef пусто, применено значение 0", driveClass);
                    return 0;
                }
                return coef;
            }
        }
        log.warn("Класс водителя '{}' не найден в drive_classes, применено значение 0", driveClass);
        return 0;
    }

    // ------------------------------------------------------------ сводные ветки

    /**
     * Сводный коэффициент пассажирской ветки (маршрут).
     *
     * <pre>
     * K = (winter + mountain + station + city + used) - roadQuality
     * multiplier = K != 0 ? 1 + 0.01*K : 1
     * </pre>
     *
     * <p>Колонки {@code mountainCoefValue}/{@code inCityCoefValue} маршрута — САМИ ЗНАЧЕНИЯ
     * коэффициентов, а не ключи справочника (эталон {@code helpers.php:137}).</p>
     *
     * @param route                 коэффициентные поля маршрута ({@code null} — множитель 1)
     * @param yearManufacture       год выпуска ТС (для износа)
     * @param indicationCounterExit показание одометра на выезде
     * @param date                  дата расчёта
     * @return разложение коэффициента
     */
    public CoefficientBreakdown passengerCoefficient(RouteCoefRef route, LocalDate yearManufacture,
                                                     Long indicationCounterExit, LocalDate date) {
        if (route == null) {
            log.warn("Пассажирский коэффициент: маршрут не задан, применён нейтральный множитель 1");
            return CoefficientBreakdown.neutral();
        }
        int winter = winterCoef(route.winterCoefId(), date);
        int mountain = route.mountainCoefValue() == null ? 0 : route.mountainCoefValue().intValue();
        int city = route.inCityCoefValue() == null ? 0 : route.inCityCoefValue().intValue();
        int station = route.stationCoef() == null ? 0 : route.stationCoef();
        int roadQuality = route.roadQuality() == null ? 0 : route.roadQuality();
        int used = usedCoef(yearManufacture, indicationCounterExit, date);
        return CoefficientBreakdown.of(winter, mountain, station, city, used, roadQuality);
    }

    /**
     * Пассажирский коэффициент с исходной проверкой зимнего периода (для сверки исторических отчётов).
     *
     * @param route                 коэффициентные поля маршрута
     * @param yearManufacture       год выпуска ТС
     * @param indicationCounterExit показание одометра на выезде
     * @param date                  дата расчёта
     * @return разложение коэффициента по правилам оригинала
     */
    public CoefficientBreakdown passengerCoefficientLegacy(RouteCoefRef route, LocalDate yearManufacture,
                                                           Long indicationCounterExit, LocalDate date) {
        if (route == null) {
            return CoefficientBreakdown.neutral();
        }
        int winter = 0;
        if (route.winterCoefId() != null) {
            Optional<WinterCoefRef> found = dict.winterCoef(route.winterCoefId());
            if (found.isPresent()
                    && isWinterPeriodLegacy(date, found.get().periodFrom(), found.get().periodTo())) {
                winter = found.get().coef() == null ? 0 : found.get().coef();
            }
        }
        int mountain = route.mountainCoefValue() == null ? 0 : route.mountainCoefValue().intValue();
        int city = route.inCityCoefValue() == null ? 0 : route.inCityCoefValue().intValue();
        int station = route.stationCoef() == null ? 0 : route.stationCoef();
        int roadQuality = route.roadQuality() == null ? 0 : route.roadQuality();
        int used = usedCoef(yearManufacture, indicationCounterExit, date);
        return CoefficientBreakdown.of(winter, mountain, station, city, used, roadQuality);
    }

    /**
     * Сводный коэффициент грузовой ветки (направление, форма 2-Б).
     *
     * <pre>
     * K = winter + direction.mountain_coef.coef + direction.in_city_coef.coef + used
     * </pre>
     * <p>Остановочный коэффициент и качество дороги в грузовой ветке НЕ участвуют.</p>
     *
     * @param direction             коэффициентные поля направления ({@code null} — множитель 1)
     * @param yearManufacture       год выпуска ТС (для износа)
     * @param indicationCounterExit показание одометра на выезде рабочего дня
     * @param date                  дата рабочего дня
     * @return разложение коэффициента
     */
    public CoefficientBreakdown cargoCoefficient(DirectionCoefRef direction, LocalDate yearManufacture,
                                                 Long indicationCounterExit, LocalDate date) {
        if (direction == null) {
            log.warn("Грузовой коэффициент: направление не задано, применён нейтральный множитель 1");
            return CoefficientBreakdown.neutral();
        }
        int winter = winterCoef(direction.winterCoefId(), date);
        int mountain = mountainCoef(direction.mountainCoefId());
        int city = cityCoef(direction.inCityCoefId());
        int used = usedCoef(yearManufacture, indicationCounterExit, date);
        return CoefficientBreakdown.of(winter, mountain, 0, city, used, 0);
    }
}
