package tj.mintrans.epd.waybill.calc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tj.mintrans.epd.waybill.calc.model.BrandNorms;
import tj.mintrans.epd.waybill.calc.model.CargoFuelRequest;
import tj.mintrans.epd.waybill.calc.model.FuelNorm;
import tj.mintrans.epd.waybill.calc.model.FuelNormRequest;
import tj.mintrans.epd.waybill.calc.model.FuelNormResult;
import tj.mintrans.epd.waybill.calc.model.FuelRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Расчёт нормативного расхода топлива — дословный перенос движка «Роҳхат»
 * ({@code tj.etrans.rohkhat.calc.FuelCalculationService}).
 *
 * <p>Базовая норма берётся из {@code brands.fuel_100} (или {@code brands.fuel_100_dushanbe}
 * при {@code routes.excluding_coef}); почасовая — из {@code brands.fuel_hour}.
 * Надбавки: кондиционер, отопление салона, спецработа.</p>
 *
 * <p>Эталон: docs/spec/07-calculations.md §1.3 ({@code helpers.php:60, fuel_calc()}),
 * §1.5 ({@code helpers.php:294, fuel_calc_100()}), §1.6 ({@code helpers.php:172,
 * fuel_calc_100_day()}), §3.5 ({@code LabadorFuel.php}, {@code SelfUnloadFuel.php},
 * {@code SpecialFuel.php}, {@code SpecialMoverFuel.php}).</p>
 *
 * <p>Отличие переноса от rohkhat-v2: методы {@link #resolveNorms} и {@link #hourlyNorm}
 * принимают {@link BrandNorms} вместо сущности {@code Brand} — ядро не зависит от модели
 * справочника марок.</p>
 */
@Service
public class FuelNormCalculator {

    private static final Logger log = LoggerFactory.getLogger(FuelNormCalculator.class);

    /** Виды топлива, которые возвращает {@code fuel_calc()}: 1 = бензин, 2 = дизель, 3 = газ. */
    static final long[] FUEL_IDS = {1L, 2L, 3L};

    private static final ObjectMapper JSON = new ObjectMapper();

    // ------------------------------------------------------------------ разбор JSON

    /**
     * Разбор JSON-массива нормативов марки.
     *
     * <p>Числовые поля в унаследованных данных могут быть строками с запятой в роли
     * десятичного разделителя — обрабатывается через {@link CalcUtils#parseLegacyNumber(String)}.</p>
     *
     * @param json содержимое колонки ({@code null}/пусто допустимы)
     * @return список нормативов, пустой при отсутствии либо некорректном JSON
     */
    public List<FuelNorm> parseNorms(String json) {
        List<FuelNorm> result = new ArrayList<>();
        JsonNode root = readArray(json, "нормативы марки");
        if (root == null) {
            return result;
        }
        for (JsonNode node : root) {
            long fuelId = (long) number(node, "fuel_id");
            if (fuelId == 0L) {
                log.warn("Норматив расхода без fuel_id пропущен: {}", node);
                continue;
            }
            result.add(new FuelNorm(
                    fuelId,
                    number(node, "consumption"),
                    number(node, "ton_for_100"),
                    number(node, "s_for_rais"),
                    number(node, "work_for_hour"),
                    number(node, "consumption_for_special")));
        }
        return result;
    }

    /**
     * Разбор JSON-массива топлива путевого листа / рабочего дня ({@code fuels}, {@code fuel}).
     *
     * @param json содержимое колонки ({@code null}/пусто допустимы)
     * @return список строк топлива, пустой при отсутствии либо некорректном JSON
     */
    public List<FuelRow> parseFuelRows(String json) {
        List<FuelRow> result = new ArrayList<>();
        JsonNode root = readArray(json, "топливо путевого листа");
        if (root == null) {
            return result;
        }
        for (JsonNode node : root) {
            long fuelId = (long) number(node, "fuel_id");
            if (fuelId == 0L) {
                log.warn("Строка топлива без fuel_id пропущена: {}", node);
                continue;
            }
            result.add(new FuelRow(
                    fuelId,
                    number(node, "fuel_given"),
                    number(node, "coef_below_0"),
                    number(node, "additional"),
                    number(node, "remain_fuel_before_exit"),
                    number(node, "remain_fuel_entry"),
                    number(node, "consumption")));
        }
        return result;
    }

    private JsonNode readArray(String json, String what) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(json);
            if (root == null || !root.isArray()) {
                log.warn("Ожидался JSON-массив ({}), получено: {}", what, json);
                return null;
            }
            return root;
        } catch (JsonProcessingException ex) {
            log.warn("Не удалось разобрать JSON ({}): {}", what, ex.getMessage());
            return null;
        }
    }

    private static double number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return 0d;
        }
        if (value.isNumber()) {
            return value.doubleValue();
        }
        return CalcUtils.parseLegacyNumber(value.asText(""));
    }

    // ---------------------------------------------------------------- нормативы марки

    /**
     * Выбор таблицы нормативов марки.
     *
     * <p>Оригинал: {@code helpers.php:300, fuel_calc_100()} —
     * {@code route.excluding_coef ? brand.fuel_100_dushanbe : brand.fuel_100}.</p>
     *
     * @param brand         нормативы марки ТС ({@code null} допустим)
     * @param excludingCoef признак «душанбинского» норматива ({@code routes.excluding_coef})
     * @return список нормативов, пустой при отсутствии данных
     */
    public List<FuelNorm> resolveNorms(BrandNorms brand, boolean excludingCoef) {
        if (brand == null) {
            log.warn("Нормативы расхода: марка ТС не найдена, применён пустой список");
            return List.of();
        }
        // Оригинал проверяет непустоту именно fuel_100 (даже для душанбинской ветки).
        if (brand.fuel100() == null || brand.fuel100().isBlank()) {
            log.warn("Нормативы расхода: у марки id={} не заполнено fuel_100", brand.brandId());
            return List.of();
        }
        String json = excludingCoef ? brand.fuel100Dushanbe() : brand.fuel100();
        List<FuelNorm> norms = parseNorms(json);
        if (norms.isEmpty()) {
            log.warn("Нормативы расхода: у марки id={} пустая таблица нормативов (excludingCoef={})",
                    brand.brandId(), excludingCoef);
        }
        return norms;
    }

    /**
     * Поиск норматива по виду топлива.
     *
     * @param norms  список нормативов
     * @param fuelId вид топлива
     * @return найденный норматив либо {@link Optional#empty()}
     */
    public Optional<FuelNorm> findNorm(List<FuelNorm> norms, long fuelId) {
        if (norms == null) {
            return Optional.empty();
        }
        // Оригинал: array_column($fuels, 'consumption', 'fuel_id') — при дубле fuel_id
        // в brands.fuel_100 остаётся ПОСЛЕДНЯЯ строка. Воспроизводим ту же семантику.
        return norms.stream().filter(n -> n.fuelId() == fuelId).reduce((first, last) -> last);
    }

    // ------------------------------------------------------------------- выданное

    /**
     * Сумма выданного топлива по видам.
     *
     * <p>Оригинал: {@code helpers.php:60, fuel_calc()}:</p>
     * <pre>
     * fuel = (float) row.fuel_given
     * если row.coef_below_0 != 0:  fuel += row.coef_below_0
     * result[row.fuel_id] = fuel
     * return { 1: result[1] ?? 0, 2: result[2] ?? 0, 3: result[3] ?? 0 }
     * </pre>
     * <p>При нескольких строках одного вида топлива побеждает последняя — как в оригинале.</p>
     *
     * @param rows строки топлива путевого листа
     * @return карта {@code fuel_id -> выдано, л}; ключи 1, 2, 3 присутствуют всегда
     */
    public Map<Long, Double> givenByFuel(List<FuelRow> rows) {
        Map<Long, Double> result = new LinkedHashMap<>();
        for (long fuelId : FUEL_IDS) {
            result.put(fuelId, 0d);
        }
        if (rows == null) {
            return result;
        }
        for (FuelRow row : rows) {
            double fuel = row.fuelGiven();
            if (row.coefBelow0() != 0d) {
                fuel += row.coefBelow0();
            }
            if (result.containsKey(row.fuelId())) {
                result.put(row.fuelId(), fuel);
            } else {
                log.warn("Вид топлива fuel_id={} вне диапазона 1..3 не учитывается (как в оригинале)",
                        row.fuelId());
            }
        }
        return result;
    }

    // ------------------------------------------------------- нормативный расход (пасс.)

    /**
     * Нормативный расход топлива по одному виду топлива (пассажирская ветка).
     *
     * <p>Оригинал: {@code helpers.php:294, fuel_calc_100()}.</p>
     *
     * <p>Ветка A — {@code routes.excluding_coef = true} (Душанбе, БЕЗ коэффициентов):</p>
     * <pre>
     * Ma = 0.01 * (fuel + additional_fuel_100 + additional_fuel) * gashti_umumi
     *      + route.cond_fuel + route.heating_fuel
     * </pre>
     *
     * <p>Ветка B — обычная (С коэффициентами):</p>
     * <pre>
     * Ma = 0.01 * (fuel + additional_fuel_100) * gashti_umumi * (1 + 0.01*K)
     *      + cond_fuel + warm_salon + special_work
     * fuel_100 = (fuel + additional_fuel_100) * (1 + 0.01*K) + cond_fuel + warm_salon + special_work
     * </pre>
     * <p>Итог: {@code Ma = Ma > 0 ? Ma : 0}.</p>
     *
     * <p>РАСХОЖДЕНИЯ С ОРИГИНАЛОМ (ветка B) — как в rohkhat-v2:
     * {@code cond_fuel} по корректной формуле {@link #conditionerFuel}; {@code warm_salon}
     * восстановлен по {@code brands.fuel_interior_heating} через {@link #interiorHeatingFuel}.</p>
     *
     * @param request вход расчёта
     * @return нормативный расход и составляющие надбавок
     */
    public FuelNormResult calcNorm(FuelNormRequest request) {
        if (request == null) {
            log.warn("Расчёт норматива: пустой запрос, возвращён нулевой результат");
            return FuelNormResult.zero(0L);
        }
        double base = request.baseNorm100();
        double distance = request.distanceKm();

        if (request.excludingCoef()) {
            double per100 = base + request.additionalFuel100() + request.additionalFuel();
            double ma = 0.01 * per100 * distance + request.routeCondFuel() + request.routeHeatingFuel();
            return new FuelNormResult(request.fuelId(), Math.max(ma, 0d), per100,
                    request.routeCondFuel(), request.routeHeatingFuel(), 0d);
        }

        double cond = conditionerFuel(request.conditionerHours(), request.workHours(),
                request.airConditionerPercent(), base, distance);
        double heating = interiorHeatingFuel(request.interiorHeatingPerHour(), request.workHours());
        double special = request.specialWorkFuel();
        double multiplier = request.coefficientMultiplier();

        double ma = 0.01 * (base + request.additionalFuel100()) * distance * multiplier
                + cond + heating + special;
        double per100 = (base + request.additionalFuel100()) * multiplier + cond + heating + special;

        return new FuelNormResult(request.fuelId(), Math.max(ma, 0d), per100, cond, heating, special);
    }

    /**
     * Перерасход (+) / экономия (−) относительно норматива.
     *
     * <p>Оригинал: {@code helpers.php:172, fuel_calc_100_day()} —
     * {@code fuel_use = Ma > 0 ? fuel_give - Ma : fuel_give}.</p>
     *
     * @param fuelGiven  выдано топлива, л
     * @param normLiters нормативный расход {@code Ma}, л
     * @return разница «выдано − норматив», либо выданное при нулевом нормативе
     */
    public static double fuelUse(double fuelGiven, double normLiters) {
        return normLiters > 0 ? fuelGiven - normLiters : fuelGiven;
    }

    // -------------------------------------------------------------------- надбавки

    /**
     * Надбавка на кондиционер, л.
     *
     * <p>Корректная формула (в оригинале закомментирована, {@code helpers.php:378}):</p>
     * <pre>
     * cond_fuel = (cond_h / work_h) * (parking.air_conditioner * 0.01) * 0.01 * fuel * gashti_umumi
     * </pre>
     *
     * @param conditionerHours      время работы кондиционера, ч
     * @param workHours             время работы ТС, ч
     * @param airConditionerPercent {@code parkings.air_conditioner}, %
     * @param baseNorm100           базовая норма, л/100 км
     * @param distanceKm            пробег, км
     * @return надбавка, л
     */
    public static double conditionerFuel(double conditionerHours, double workHours,
                                         int airConditionerPercent, double baseNorm100, double distanceKm) {
        if (workHours <= 0d) {
            return 0d;
        }
        double share = CalcUtils.safeDivide(conditionerHours, workHours, 0d);
        return share * (airConditionerPercent * 0.01) * 0.01 * baseNorm100 * distanceKm;
    }

    /**
     * Дословное воспроизведение надбавки на кондиционер из оригинала
     * ({@code helpers.php:379} — фактически {@code work_h > 0 ? cond_h / work_h : 0}).
     *
     * @param conditionerHours время работы кондиционера, ч
     * @param workHours        время работы ТС, ч
     * @return безразмерная доля времени (поведение оригинала)
     */
    public static double conditionerFuelLegacy(double conditionerHours, double workHours) {
        return workHours > 0d ? CalcUtils.safeDivide(conditionerHours, workHours, 0d) : 0d;
    }

    /**
     * Надбавка на отопление салона, л.
     *
     * <p>Оригинал (закомментирован, {@code helpers.php:348}):
     * {@code warm_salon = brand.fuel_interior_heating * (work_time / 60)}.</p>
     *
     * @param interiorHeatingPerHour {@code brands.fuel_interior_heating}, л/ч
     * @param workHours              время работы, ч
     * @return надбавка, л
     */
    public static double interiorHeatingFuel(double interiorHeatingPerHour, double workHours) {
        if (interiorHeatingPerHour <= 0d || workHours <= 0d) {
            return 0d;
        }
        return interiorHeatingPerHour * workHours;
    }

    /**
     * Почасовая норма расхода из {@code brands.fuel_hour} — надбавка на спецработу на стоянке.
     *
     * @param brand  нормативы марки ТС
     * @param fuelId вид топлива
     * @param hours  часы работы оборудования
     * @return расход, л; 0 при отсутствии норматива
     */
    public double hourlyNorm(BrandNorms brand, long fuelId, double hours) {
        if (brand == null || brand.fuelHour() == null || brand.fuelHour().isBlank()) {
            return 0d;
        }
        if (hours <= 0d) {
            return 0d;
        }
        Optional<FuelNorm> norm = findNorm(parseNorms(brand.fuelHour()), fuelId);
        if (norm.isEmpty()) {
            log.warn("Почасовая норма: у марки id={} нет строки fuel_hour для fuel_id={}",
                    brand.brandId(), fuelId);
            return 0d;
        }
        return norm.get().consumption() * hours;
    }

    // ------------------------------------------------------------- грузовые формулы

    /**
     * Расход бортовых автомобилей, фургонов и тягачей (коды марки 1, 2, 4).
     *
     * <p>Оригинал: {@code LabadorFuel.php:38, calcWorkDays()}:</p>
     * <pre>
     * result = 0.01 * (m_base_100 * l_dist + m_tkm * P) * coef
     * если hasYadak: result += parking.weight_ydak * l_dist / 100 * m_tkm  (+ weight_ydak_2 для 5Б-БМ)
     * если type == '5': result *= 1.1
     * </pre>
     *
     * @param request вход расчёта
     * @return расход, л
     */
    public double labadorFuel(CargoFuelRequest request) {
        FuelNorm norm = requireNorm(request);
        double result = 0.01 * (norm.consumption() * request.distanceKm()
                + norm.tonFor100() * request.transportWork()) * request.coefficient();
        if (request.hasTrailer()) {
            result += request.trailerWeight() * request.distanceKm() / 100 * norm.tonFor100();
            result += request.trailerWeight2() * request.distanceKm() / 100 * norm.tonFor100();
        }
        if (request.vanSurcharge()) {
            result *= 1.1;
        }
        return result;
    }

    /**
     * Расход самосвалов (код марки 3).
     *
     * <p>Оригинал: {@code SelfUnloadFuel.php:43, calcWorkDays()}:</p>
     * <pre>
     * result = 0.01 * (m_base_100 * l_dist + m_tkm * P) * coef + (s_for_rais * Z)
     * если hasYadak: result += m_tkm * (weight_ydak + 0.5 * carrying_ydak)
     * </pre>
     *
     * @param request вход расчёта
     * @return расход, л
     */
    public double selfUnloadFuel(CargoFuelRequest request) {
        FuelNorm norm = requireNorm(request);
        double result = 0.01 * (norm.consumption() * request.distanceKm()
                + norm.tonFor100() * request.transportWork()) * request.coefficient()
                + norm.sForRais() * request.trips();
        if (request.hasTrailer()) {
            result += norm.tonFor100() * (request.trailerWeight() + 0.5 * request.trailerCarrying());
            if (request.trailerWeight2() != 0d) {
                result += norm.tonFor100() * (request.trailerWeight2() + 0.5 * request.trailerCarrying());
            }
        }
        return result;
    }

    /**
     * Расход спецтехники, работающей на стоянке (код марки 5).
     *
     * <p>Оригинал: {@code SpecialFuel.php:39, calcWorkDays()}:</p>
     * <pre>
     * result = (0.01 * m_base_100 * l_dist + work_for_hour * work_time) * coef
     * если hasYadak: result += m_tkm * P / 100
     * </pre>
     *
     * @param request вход расчёта
     * @return расход, л
     */
    public double specialFuel(CargoFuelRequest request) {
        FuelNorm norm = requireNorm(request);
        double result = (0.01 * norm.consumption() * request.distanceKm()
                + norm.workForHour() * request.specialWorkHours()) * request.coefficient();
        if (request.hasTrailer()) {
            result += norm.tonFor100() * request.transportWork() / 100;
        }
        return result;
    }

    /**
     * Расход спецтехники, работающей в движении (код марки 8).
     *
     * <p>Оригинал: {@code SpecialMoverFuel.php, calcWorkDays()}:</p>
     * <pre>
     * result = 0.01 * (m_base_100 * l_dist + consumption_for_special * L1) * coef + s_for_rais * N
     * </pre>
     *
     * @param request вход расчёта
     * @return расход, л
     */
    public double specialMoverFuel(CargoFuelRequest request) {
        FuelNorm norm = requireNorm(request);
        return 0.01 * (norm.consumption() * request.distanceKm()
                + norm.consumptionForSpecial() * request.specialDistance()) * request.coefficient()
                + norm.sForRais() * request.trips();
    }

    private FuelNorm requireNorm(CargoFuelRequest request) {
        if (request == null || request.norm() == null) {
            log.warn("Грузовой расчёт топлива: норматив марки не задан, применена норма 1 л/100 км "
                    + "(как в оригинале: $m_base_100 = 1 при отсутствии строки)");
            return FuelNorm.ofBase(0L, 1d);
        }
        return request.norm();
    }

    // --------------------------------------------------------------------- остаток

    /**
     * Остаток топлива при возврате (физически верный вариант, {@code LabadorFuel.php:45}):
     * {@code round(remain_before_exit + fuel_given - consumed, 4)}.
     *
     * @param remainBeforeExit остаток до выезда, л
     * @param fuelGiven        выдано (включая дозаправку), л
     * @param consumed         нормативный расход, л
     * @return остаток при возврате, округлён до 4 знаков
     */
    public static double remainFuelEntry(double remainBeforeExit, double fuelGiven, double consumed) {
        return CalcUtils.round(remainBeforeExit + fuelGiven - consumed, CalcUtils.FUEL_SCALE);
    }

    /**
     * Дословное воспроизведение формулы остатка из хука сохранения путевого листа
     * ({@code BillNumberTrait::updating} — перевёрнутые знаки, округление до 3 знаков).
     *
     * @param normLiters       нормативный расход, л
     * @param additional       дозаправка, л
     * @param fuelGiven        выдано, л
     * @param remainBeforeExit остаток до выезда, л
     * @return значение, которое пишет в БД оригинал
     */
    public static double remainFuelEntryLegacy(double normLiters, double additional,
                                               double fuelGiven, double remainBeforeExit) {
        return CalcUtils.round((normLiters + additional) - fuelGiven + remainBeforeExit, 3);
    }
}
