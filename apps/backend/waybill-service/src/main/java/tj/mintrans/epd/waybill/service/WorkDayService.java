package tj.mintrans.epd.waybill.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.domain.FuelRecord;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.domain.WorkDay;
import tj.mintrans.epd.waybill.repository.FuelRecordRepository;
import tj.mintrans.epd.waybill.repository.WorkDayRepository;
import tj.mintrans.epd.waybill.web.error.ApiErrors.ConflictException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Многодневные путевые листы: рабочие дни (по строке на день, legacy-формы 1-А/2-Б)
 * и учёт топлива (лимит видов топлива на ПЛ — по форме, {@link tj.mintrans.epd.waybill.domain.WaybillType#maxFuelTypes()}).
 */
@Service
public class WorkDayService {

    private final WorkDayRepository workDays;
    private final FuelRecordRepository fuelRecords;
    private final WaybillService waybillService;
    /** Проверять лимит суточного пробега ({@code epd.limits.daily-km-enabled}, MIGRATION.md 12.5). */
    private final boolean dailyKmEnabled;

    public WorkDayService(WorkDayRepository workDays,
                          FuelRecordRepository fuelRecords,
                          WaybillService waybillService,
                          @org.springframework.beans.factory.annotation.Value("${epd.limits.daily-km-enabled:false}") boolean dailyKmEnabled) {
        this.workDays = workDays;
        this.fuelRecords = fuelRecords;
        this.waybillService = waybillService;
        this.dailyKmEnabled = dailyKmEnabled;
    }

    @Transactional
    public WorkDay addWorkDay(UUID waybillId, LocalDate workDate, LocalTime exitTime, LocalTime entryTime,
                              Integer odometerExit, Integer odometerEntry, Integer laps, BigDecimal revenue) {
        return addWorkDay(waybillId, workDate, exitTime, entryTime, odometerExit, odometerEntry, laps, revenue,
                null, null, null);
    }

    /**
     * С посуточными данными для расчёта топлива многодневных пассажирских ПЛ (кондиционер,
     * клиент/время у клиента) — пока только хранение, движок расчёта их ещё не читает
     * (см. spec/notes/04-гэп-анализ §2.2: живой путь считает агрегатом на весь ПЛ).
     */
    @Transactional
    public WorkDay addWorkDay(UUID waybillId, LocalDate workDate, LocalTime exitTime, LocalTime entryTime,
                              Integer odometerExit, Integer odometerEntry, Integer laps, BigDecimal revenue,
                              BigDecimal conditionerHours, UUID clientId, LocalTime clientTime) {
        return addWorkDay(waybillId, workDate, exitTime, entryTime, odometerExit, odometerEntry, laps, revenue,
                conditionerHours, clientId, clientTime, null, null);
    }

    /**
     * Полный рабочий день legacy 1-А/3-С/2-Б: плюс селекторы «гашти ибтидоӣ» начала/конца смены
     * ({@code begin_path_a}/{@code begin_path_b} — какой нулевой пробег маршрута входит в пробег дня).
     */
    @Transactional
    public WorkDay addWorkDay(UUID waybillId, LocalDate workDate, LocalTime exitTime, LocalTime entryTime,
                              Integer odometerExit, Integer odometerEntry, Integer laps, BigDecimal revenue,
                              BigDecimal conditionerHours, UUID clientId, LocalTime clientTime,
                              String beginPathA, String beginPathB) {
        var wb = waybillService.get(waybillId);
        if (wb.getStatus() != WaybillStatus.ACTIVE && !waybillService.isOverdueOnLine(wb)) {
            throw new ConflictException("Рабочий день можно добавить только листу на линии (текущий статус: %s)".formatted(wb.getStatus()));
        }
        // Окно дат — срок листа в календарных днях от даты выпуска: 3-С — 7 дней (legacy exit + 6),
        // 1-А — 4 (exit + 3), 2-Б — 15 (exit + 14), 3-С «30» — 30. Раньше бралась дата validTo
        // (= выпуск + N суток), и принимался лишний (N+1)-й день.
        long days = WaybillService.validityDays(wb);
        var from = wb.getValidFrom().toLocalDate();
        var to = from.plusDays(Math.max(0, days - 1));
        if (workDate.isBefore(from) || workDate.isAfter(to)) {
            throw new UnprocessableException("Дата рабочего дня вне срока действия путевого листа (%s — %s)".formatted(from, to));
        }
        if (workDays.existsByWaybillIdAndWorkDate(waybillId, workDate)) {
            throw new ConflictException("Рабочий день на дату %s уже добавлен".formatted(workDate));
        }
        if (workDays.countByWaybillId(waybillId) >= Math.max(1, days)) {
            throw new UnprocessableException("Число рабочих дней превышает срок листа: %d".formatted(days));
        }
        WaybillService.assertBeginPath(beginPathA, true);
        WaybillService.assertBeginPath(beginPathB, false);
        if (laps != null && (laps < 0 || laps > 99)) {
            throw new UnprocessableException("Число кругов (рейсов) — от 0 до 99");
        }
        if (revenue != null && revenue.signum() < 0) {
            throw new UnprocessableException("Выручка не может быть отрицательной");
        }
        if (odometerExit != null && odometerEntry != null && odometerEntry < odometerExit) {
            throw new UnprocessableException("Одометр возврата меньше одометра выезда");
        }
        // Лимит суточного пробега (MIGRATION.md 12.5, legacy max_counter_value 650 для 3-С/1-А) — по флагу.
        int maxDailyKm = wb.getWaybillType().maxDailyKm();
        if (dailyKmEnabled && maxDailyKm > 0 && odometerExit != null && odometerEntry != null
                && odometerEntry - odometerExit > maxDailyKm) {
            throw new UnprocessableException("Суточный пробег %d км превышает лимит формы %s — %d км"
                    .formatted(odometerEntry - odometerExit, wb.getWaybillType().legacyForm(), maxDailyKm));
        }
        var day = new WorkDay();
        day.setWaybillId(waybillId);
        day.setWorkDate(workDate);
        day.setExitTime(exitTime);
        day.setEntryTime(entryTime);
        day.setOdometerExit(odometerExit);
        day.setOdometerEntry(odometerEntry);
        day.setLaps(laps);
        day.setRevenue(revenue);
        day.setConditionerHours(conditionerHours);
        day.setClientId(clientId);
        day.setClientTime(clientTime);
        day.setBeginPathA(beginPathA == null || beginPathA.isBlank() ? null : beginPathA.trim());
        day.setBeginPathB(beginPathB == null || beginPathB.isBlank() ? null : beginPathB.trim());
        return workDays.save(day);
    }

    /**
     * Правка рабочего дня: до закрытия листа (на линии, возвращён, просрочен на линии) — как в legacy,
     * где строки work_days редактировались в форме листа. Проверки те же, что при добавлении.
     */
    @Transactional
    public WorkDay updateWorkDay(UUID waybillId, UUID dayId, LocalDate workDate, LocalTime exitTime, LocalTime entryTime,
                                 Integer odometerExit, Integer odometerEntry, Integer laps, BigDecimal revenue,
                                 BigDecimal conditionerHours, UUID clientId, LocalTime clientTime,
                                 String beginPathA, String beginPathB) {
        var wb = waybillService.get(waybillId);
        requireEditable(wb);
        var day = ownDay(waybillId, dayId);
        long days = WaybillService.validityDays(wb);
        var from = wb.getValidFrom().toLocalDate();
        var to = from.plusDays(Math.max(0, days - 1));
        if (workDate.isBefore(from) || workDate.isAfter(to)) {
            throw new UnprocessableException("Дата рабочего дня вне срока действия путевого листа (%s — %s)".formatted(from, to));
        }
        if (!workDate.equals(day.getWorkDate()) && workDays.existsByWaybillIdAndWorkDate(waybillId, workDate)) {
            throw new ConflictException("Рабочий день на дату %s уже добавлен".formatted(workDate));
        }
        if (odometerExit != null && odometerEntry != null && odometerEntry < odometerExit) {
            throw new UnprocessableException("Одометр возврата меньше одометра выезда");
        }
        WaybillService.assertBeginPath(beginPathA, true);
        WaybillService.assertBeginPath(beginPathB, false);
        if (laps != null && (laps < 0 || laps > 99)) {
            throw new UnprocessableException("Число кругов (рейсов) — от 0 до 99");
        }
        if (revenue != null && revenue.signum() < 0) {
            throw new UnprocessableException("Выручка не может быть отрицательной");
        }
        day.setWorkDate(workDate);
        day.setExitTime(exitTime);
        day.setEntryTime(entryTime);
        day.setOdometerExit(odometerExit);
        day.setOdometerEntry(odometerEntry);
        day.setLaps(laps);
        day.setRevenue(revenue);
        day.setConditionerHours(conditionerHours);
        day.setClientId(clientId);
        day.setClientTime(clientTime);
        day.setBeginPathA(beginPathA == null || beginPathA.isBlank() ? null : beginPathA.trim());
        day.setBeginPathB(beginPathB == null || beginPathB.isBlank() ? null : beginPathB.trim());
        return workDays.save(day);
    }

    /**
     * Время работы спецоборудования за день (legacy 2-Б / 5Б-БМ work_time; сверка 25.09, B4) — только у грузовых
     * листов; {@code null} очищает. Входит в норму топлива на спецработу и печатается на бланке.
     */
    @Transactional
    public WorkDay setSpecialWorkTime(UUID waybillId, UUID dayId, LocalTime time) {
        var wb = waybillService.get(waybillId);
        var day = ownDay(waybillId, dayId);
        if (time != null) {
            var t = wb.getWaybillType();
            boolean cargo = t == tj.mintrans.epd.waybill.domain.WaybillType.WB_TRUCK
                    || t == tj.mintrans.epd.waybill.domain.WaybillType.WB_TRUCK_INTL
                    || t == tj.mintrans.epd.waybill.domain.WaybillType.WB_SPECIAL
                    || t == tj.mintrans.epd.waybill.domain.WaybillType.WB_DANGEROUS;
            if (!cargo) {
                throw new UnprocessableException("Время спецоборудования указывается только в грузовых листах");
            }
        }
        day.setSpecialWorkTime(time);
        return workDays.save(day);
    }

    /** Удаление рабочего дня вместе с привязанными к нему строками топлива. */
    @Transactional
    public void deleteWorkDay(UUID waybillId, UUID dayId) {
        var wb = waybillService.get(waybillId);
        requireEditable(wb);
        var day = ownDay(waybillId, dayId);
        fuelRecords.findByWaybillIdOrderByCreatedAt(waybillId).stream()
                .filter(f -> dayId.equals(f.getWorkDayId()))
                .forEach(fuelRecords::delete);
        workDays.delete(day);
    }

    /** Правка строки топлива (legacy fuels редактировались до закрытия листа). Остаток после возврата пересчитывается. */
    @Transactional
    public FuelRecord updateFuel(UUID waybillId, UUID fuelId, UUID workDayId, short fuelType,
                                 BigDecimal fuelGiven, BigDecimal remainBeforeExit,
                                 BigDecimal additionalGiven, BigDecimal returned, BigDecimal coefBelow0) {
        var wb = waybillService.get(waybillId);
        if (wb.getStatus().isTerminal() && !waybillService.isOverdueOnLine(wb)) {
            throw new ConflictException("Изменение топлива невозможно в статусе " + wb.getStatus());
        }
        var record = ownFuel(waybillId, fuelId);
        if (isNegative(fuelGiven) || isNegative(remainBeforeExit) || isNegative(additionalGiven)
                || isNegative(returned) || isNegative(coefBelow0)) {
            throw new UnprocessableException("Объёмы топлива не могут быть отрицательными");
        }
        if (record.getFuelType() != fuelType) {
            throw new UnprocessableException("Вид топлива строки не меняется — удалите строку и добавьте новую");
        }
        int maxAdditional = wb.getWaybillType().maxAdditionalFuelLiters();
        if (maxAdditional >= 0 && additionalGiven != null && additionalGiven.compareTo(BigDecimal.valueOf(maxAdditional)) > 0) {
            throw new UnprocessableException("Довыдача в пути для формы %s — не более %d л"
                    .formatted(wb.getWaybillType().legacyForm(), maxAdditional));
        }
        if (workDayId != null) {
            ownDay(waybillId, workDayId);
        }
        record.setWorkDayId(workDayId);
        record.setFuelGiven(fuelGiven);
        record.setRemainBeforeExit(remainBeforeExit);
        record.setAdditionalGiven(additionalGiven);
        record.setReturned(returned);
        record.setCoefBelow0(coefBelow0);
        return fuelRecords.save(record);
    }

    @Transactional
    public void deleteFuel(UUID waybillId, UUID fuelId) {
        var wb = waybillService.get(waybillId);
        if (wb.getStatus().isTerminal() && !waybillService.isOverdueOnLine(wb)) {
            throw new ConflictException("Удаление топлива невозможно в статусе " + wb.getStatus());
        }
        fuelRecords.delete(ownFuel(waybillId, fuelId));
    }

    /** Дни листа правятся на линии, после возврата и у просроченного на линии листа — до закрытия. */
    private void requireEditable(tj.mintrans.epd.waybill.domain.Waybill wb) {
        boolean ok = wb.getStatus() == WaybillStatus.ACTIVE || wb.getStatus() == WaybillStatus.RETURNED
                || waybillService.isOverdueOnLine(wb);
        if (!ok) {
            throw new ConflictException("Рабочие дни можно исправить только до закрытия листа (текущий статус: %s)"
                    .formatted(wb.getStatus()));
        }
    }

    private WorkDay ownDay(UUID waybillId, UUID dayId) {
        var day = workDays.findById(dayId).orElseThrow(() -> new NotFoundException("Рабочий день не найден"));
        if (!waybillId.equals(day.getWaybillId())) {
            throw new UnprocessableException("Рабочий день не относится к этому путевому листу");
        }
        return day;
    }

    private FuelRecord ownFuel(UUID waybillId, UUID fuelId) {
        var record = fuelRecords.findById(fuelId).orElseThrow(() -> new NotFoundException("Строка топлива не найдена"));
        if (!waybillId.equals(record.getWaybillId())) {
            throw new UnprocessableException("Строка топлива не относится к этому путевому листу");
        }
        return record;
    }

    public List<WorkDay> listWorkDays(UUID waybillId) {
        waybillService.get(waybillId);
        return workDays.findByWaybillIdOrderByWorkDate(waybillId);
    }

    public List<FuelRecord> listFuel(UUID waybillId) {
        return fuelRecords.findByWaybillIdOrderByCreatedAt(waybillId);
    }

    @Transactional
    public FuelRecord addFuel(UUID waybillId, UUID workDayId, short fuelType,
                              BigDecimal fuelGiven, BigDecimal remainBeforeExit, BigDecimal remainEntry) {
        return addFuel(waybillId, workDayId, fuelType, fuelGiven, remainBeforeExit, remainEntry, null, null);
    }

    /**
     * С довыдачей в пути (additionalGiven) и возвратом неиспользованного топлива на базу
     * (returned) — графы «Иловагӣ» / «Баргардонида шуд» бланков 1-А/2-Б/3-С/5Б-БМ, ранее
     * печатавшиеся пустыми (spec/notes/04-гэп-анализ).
     */
    @Transactional
    public FuelRecord addFuel(UUID waybillId, UUID workDayId, short fuelType,
                              BigDecimal fuelGiven, BigDecimal remainBeforeExit, BigDecimal remainEntry,
                              BigDecimal additionalGiven, BigDecimal returned) {
        return addFuel(waybillId, workDayId, fuelType, fuelGiven, remainBeforeExit, remainEntry,
                additionalGiven, returned, null, null);
    }

    /**
     * Полная топливная строка legacy «Роҳхат» (MIGRATION.md §5.6): плюс надбавка при температуре
     * ниже 0 °C ({@code coef_below_0}, прибавляется к выданному в расчёте) и норма к выдаче
     * ({@code be_given}, хранимое поле «Дода шавад»).
     */
    @Transactional
    public FuelRecord addFuel(UUID waybillId, UUID workDayId, short fuelType,
                              BigDecimal fuelGiven, BigDecimal remainBeforeExit, BigDecimal remainEntry,
                              BigDecimal additionalGiven, BigDecimal returned,
                              BigDecimal coefBelow0, BigDecimal beGiven) {
        var wb = waybillService.get(waybillId);
        if (wb.getStatus().isTerminal() && !waybillService.isOverdueOnLine(wb)) {
            throw new ConflictException("Добавление топлива невозможно в статусе " + wb.getStatus());
        }
        // Объёмы топлива — только неотрицательные: выданное, довыданное в пути, остатки, возврат,
        // надбавка «ниже 0» и норма к выдаче < 0 недопустимы (legacy: numeric|min:0).
        if (isNegative(fuelGiven) || isNegative(remainBeforeExit) || isNegative(remainEntry)
                || isNegative(additionalGiven) || isNegative(returned)
                || isNegative(coefBelow0) || isNegative(beGiven)) {
            throw new UnprocessableException("Объёмы топлива не могут быть отрицательными");
        }
        // Электротранспорт (троллейбус) — только электроэнергия (вид 5), горючее ему не выдают.
        if (wb.getWaybillType() == tj.mintrans.epd.waybill.domain.WaybillType.WB_TROLLEYBUS && fuelType != 5) {
            throw new UnprocessableException("Для электротранспорта учитывается только электроэнергия (вид 5)");
        }
        // Вид топлива заправки должен соответствовать карточке ТС (снимок мастер-данных, поле fuel_type
        // миграции V47). Несовместимые основные виды между собой не путаем — напр. бензин в дизельный ТС.
        // Газ (сжиженный/природный) не блокируем: это штатное второе топливо газобаллонного оборудования,
        // из-за которого на ПЛ и допускается до двух видов топлива (см. MAX_FUEL_TYPES).
        var vehicleSnapshot = wb.getVehicleSnapshot();
        Integer vehicleFuel = intOrNull(vehicleSnapshot == null ? null : vehicleSnapshot.get("fuelType"));
        if (vehicleFuel != null && vehicleFuel.intValue() != fuelType
                && !isGasFuel(fuelType) && !isGasFuel(vehicleFuel.shortValue())) {
            throw new UnprocessableException(
                    "Вид топлива заправки (%d) не соответствует виду топлива транспортного средства (%d)"
                            .formatted(fuelType, vehicleFuel));
        }
        if (workDayId != null) {
            var day = workDays.findById(workDayId)
                    .orElseThrow(() -> new NotFoundException("Рабочий день не найден"));
            if (!waybillId.equals(day.getWaybillId())) {
                throw new UnprocessableException("Рабочий день не относится к этому путевому листу");
            }
            // Дата заправки берётся из рабочего дня (у самой записи топлива своего поля даты нет —
            // created_at проставляется автоматически). Заправку нельзя оформить будущим числом.
            if (day.getWorkDate() != null && day.getWorkDate().isAfter(LocalDate.now())) {
                throw new UnprocessableException("Дата заправки не может быть в будущем");
            }
        }
        // Довыдача в пути «Харҷи иловагӣ» для 1-АД ограничена 0…5 л (legacy additional_value / between:0,5;
        // MIGRATION.md 12.8); у остальных форм — только неотрицательность (выше).
        int maxAdditional = wb.getWaybillType().maxAdditionalFuelLiters();
        if (maxAdditional >= 0 && additionalGiven != null && additionalGiven.compareTo(BigDecimal.valueOf(maxAdditional)) > 0) {
            throw new UnprocessableException("Довыдача в пути для формы %s — не более %d л"
                    .formatted(wb.getWaybillType().legacyForm(), maxAdditional));
        }
        // Лимит видов топлива на ПЛ по форме (MIGRATION.md 12.4, legacy fuels … max:N): 2-Б/5Б-БМ — 1,
        // пассажирские — 2 (две топливные секции бланка).
        var existing = fuelRecords.findByWaybillIdOrderByCreatedAt(waybillId);
        long distinctTypes = existing.stream().map(FuelRecord::getFuelType).distinct().count();
        boolean newType = existing.stream().noneMatch(r -> r.getFuelType() == fuelType);
        int maxFuelTypes = wb.getWaybillType().maxFuelTypes();
        if (newType && distinctTypes >= maxFuelTypes) {
            throw new UnprocessableException("Для формы %s допускается не более %d вид(ов) топлива на путевой лист"
                    .formatted(wb.getWaybillType().legacyForm(), maxFuelTypes));
        }
        var record = new FuelRecord();
        record.setWaybillId(waybillId);
        record.setWorkDayId(workDayId);
        record.setFuelType(fuelType);
        record.setFuelGiven(fuelGiven);
        record.setRemainBeforeExit(remainBeforeExit);
        record.setRemainEntry(remainEntry);
        record.setAdditionalGiven(additionalGiven);
        record.setReturned(returned);
        record.setCoefBelow0(coefBelow0);
        record.setBeGiven(beGiven);
        return fuelRecords.save(record);
    }

    private static boolean isNegative(BigDecimal v) {
        return v != null && v.signum() < 0;
    }

    /** Газовое топливо (3=сжиженный, 4=природный) — штатное второе топливо ГБО, с карточкой ТС не сверяется. */
    private static boolean isGasFuel(short fuelType) {
        return fuelType == 3 || fuelType == 4;
    }

    private static Integer intOrNull(Object o) {
        if (o == null) {
            return null;
        }
        return o instanceof Number n ? n.intValue() : Integer.valueOf(o.toString());
    }
}
