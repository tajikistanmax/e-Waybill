package tj.mintrans.epd.waybill.calc;

import tj.mintrans.epd.waybill.calc.model.DriveClassRef;
import tj.mintrans.epd.waybill.calc.model.SimpleCoefRef;
import tj.mintrans.epd.waybill.calc.model.UsedCoefRef;
import tj.mintrans.epd.waybill.calc.model.WinterCoefRef;

import java.util.List;
import java.util.Optional;

/**
 * Доступ к справочникам коэффициентов расхода топлива для {@link CoefficientCalculator}.
 *
 * <p>В rohkhat-v2 {@code CoefficientService} зависел напрямую от пяти Spring Data репозиториев
 * ({@code FuelWinterCoefRepository} и др.). Здесь зависимость сведена к одному интерфейсу —
 * его реализация в мастер-данных подставляется позже, а расчётное ядро остаётся
 * тестируемым в изоляции.</p>
 */
public interface CoefficientDictionaries {

    /**
     * Запись справочника зимних коэффициентов по ключу.
     *
     * @param id ключ {@code fuel_winter_coef}
     * @return запись либо {@link Optional#empty()}
     */
    Optional<WinterCoefRef> winterCoef(long id);

    /**
     * Запись справочника горных коэффициентов по ключу.
     *
     * @param id ключ {@code mountain_coef}
     * @return запись либо {@link Optional#empty()}
     */
    Optional<SimpleCoefRef> mountainCoef(long id);

    /**
     * Запись справочника городских коэффициентов по ключу.
     *
     * @param id ключ {@code city_coef}
     * @return запись либо {@link Optional#empty()}
     */
    Optional<SimpleCoefRef> cityCoef(long id);

    /**
     * Все строки справочника коэффициентов износа ({@code used_coef}).
     *
     * @return список строк, пустой при отсутствии
     */
    List<UsedCoefRef> usedCoefRows();

    /**
     * Все строки справочника классов водителей ({@code drive_classes}).
     *
     * @return список строк, пустой при отсутствии
     */
    List<DriveClassRef> driveClasses();
}
