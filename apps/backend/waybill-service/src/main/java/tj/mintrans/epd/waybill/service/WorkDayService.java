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
        var wb = waybillService.get(waybillId);
        if (wb.getStatus() != WaybillStatus.ACTIVE) {
            throw new ConflictException("Рабочий день можно добавить только в статусе ACTIVE (текущий: %s)".formatted(wb.getStatus()));
        }
        var from = wb.getValidFrom().toLocalDate();
        var to = wb.getValidTo().toLocalDate();
        if (workDate.isBefore(from) || workDate.isAfter(to)) {
            throw new UnprocessableException("Дата рабочего дня вне срока действия путевого листа (%s — %s)".formatted(from, to));
        }
        if (workDays.existsByWaybillIdAndWorkDate(waybillId, workDate)) {
            throw new ConflictException("Рабочий день на дату %s уже добавлен".formatted(workDate));
        }
        if (workDays.countByWaybillId(waybillId) >= wb.getWaybillType().maxValidityDays()) {
            throw new UnprocessableException("Число рабочих дней превышает лимит типа: %d".formatted(wb.getWaybillType().maxValidityDays()));
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
        return workDays.save(day);
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
        if (wb.getStatus().isTerminal()) {
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
