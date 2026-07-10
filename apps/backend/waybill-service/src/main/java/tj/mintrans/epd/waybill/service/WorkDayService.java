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
 * и учёт топлива (не более двух видов на ПЛ — две топливные секции бланка).
 */
@Service
public class WorkDayService {

    /** Legacy-бланк содержит две топливные секции — не более двух видов топлива на ПЛ. */
    private static final int MAX_FUEL_TYPES = 2;

    private final WorkDayRepository workDays;
    private final FuelRecordRepository fuelRecords;
    private final WaybillService waybillService;

    public WorkDayService(WorkDayRepository workDays,
                          FuelRecordRepository fuelRecords,
                          WaybillService waybillService) {
        this.workDays = workDays;
        this.fuelRecords = fuelRecords;
        this.waybillService = waybillService;
    }

    @Transactional
    public WorkDay addWorkDay(UUID waybillId, LocalDate workDate, LocalTime exitTime, LocalTime entryTime,
                              Integer odometerExit, Integer odometerEntry, Integer laps, BigDecimal revenue) {
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
        var day = new WorkDay();
        day.setWaybillId(waybillId);
        day.setWorkDate(workDate);
        day.setExitTime(exitTime);
        day.setEntryTime(entryTime);
        day.setOdometerExit(odometerExit);
        day.setOdometerEntry(odometerEntry);
        day.setLaps(laps);
        day.setRevenue(revenue);
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
        var wb = waybillService.get(waybillId);
        if (wb.getStatus().isTerminal()) {
            throw new ConflictException("Добавление топлива невозможно в статусе " + wb.getStatus());
        }
        if (workDayId != null) {
            var day = workDays.findById(workDayId)
                    .orElseThrow(() -> new NotFoundException("Рабочий день не найден"));
            if (!waybillId.equals(day.getWaybillId())) {
                throw new UnprocessableException("Рабочий день не относится к этому путевому листу");
            }
        }
        var existing = fuelRecords.findByWaybillIdOrderByCreatedAt(waybillId);
        long distinctTypes = existing.stream().map(FuelRecord::getFuelType).distinct().count();
        boolean newType = existing.stream().noneMatch(r -> r.getFuelType() == fuelType);
        if (newType && distinctTypes >= MAX_FUEL_TYPES) {
            throw new UnprocessableException("Не более двух видов топлива на путевой лист");
        }
        var record = new FuelRecord();
        record.setWaybillId(waybillId);
        record.setWorkDayId(workDayId);
        record.setFuelType(fuelType);
        record.setFuelGiven(fuelGiven);
        record.setRemainBeforeExit(remainBeforeExit);
        record.setRemainEntry(remainEntry);
        return fuelRecords.save(record);
    }
}
