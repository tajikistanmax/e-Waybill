package tj.mintrans.epd.waybill.calc.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Рабочий день многодневного пассажирского путевого листа — формы 1-А (микроавтобус)
 * и 3-С (такси). Перенос {@code PassengerWorkDay} из ИС «Роҳхат».
 *
 * <p>{@code beginPathA}/{@code beginPathB} — СЕЛЕКТОРЫ нулевого пробега: хранят имя поля
 * маршрута ({@code "begin_path_a"} либо {@code "begin_path_b"}), а не расстояние.</p>
 *
 * <p><b>Не подключено к реальному расчёту</b> (spec/notes/04-гэп-анализ §2.2): этот
 * посуточный движок ({@link tj.mintrans.epd.waybill.calc.MultiDayPassengerCalc},
 * {@link tj.mintrans.epd.waybill.calc.SequentialFuel}) реализован и покрыт тестами,
 * но {@code WaybillCalcAssembler} его не вызывает — живой путь считает агрегатом на
 * весь ПЛ. {@code conditionerHours}/{@code clientId}/{@code clientTime} здесь заведены
 * для будущего подключения, синхронно с колонками {@code work_day} (миграция V15).</p>
 *
 * @param date                   дата рабочего дня; {@code null} → день не учитывается
 * @param laps                   выполнено кругов за день
 * @param beginPathA             селектор нулевого пробега «Гашти А»
 * @param beginPathB             селектор нулевого пробега «Гашти Б»
 * @param indicationCounterExit  показание одометра при выезде
 * @param indicationCounterEntry показание одометра при возврате
 * @param exitTime               время выезда
 * @param entryTime              время возврата
 * @param workTimeInMinutes      отработанное время, мин
 * @param conditionerHours       часы работы кондиционера за этот день
 * @param clientId               заказчик/клиент этого дня (справочник Client)
 * @param clientTime             время, проведённое у клиента за этот день
 */
public record PassengerDay(
        LocalDate date,
        Integer laps,
        String beginPathA,
        String beginPathB,
        Long indicationCounterExit,
        Long indicationCounterEntry,
        LocalTime exitTime,
        LocalTime entryTime,
        Integer workTimeInMinutes,
        BigDecimal conditionerHours,
        UUID clientId,
        LocalTime clientTime
) {
}
