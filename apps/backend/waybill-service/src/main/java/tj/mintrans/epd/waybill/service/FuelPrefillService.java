package tj.mintrans.epd.waybill.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import tj.mintrans.epd.waybill.domain.FuelRecord;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.repository.FuelRecordRepository;
import tj.mintrans.epd.waybill.repository.WaybillRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Автоподстановка топливной строки из предыдущего путевого листа того же ТС — перенос 1-в-1
 * legacy «Роҳхат» (MIGRATION.md §4.9): {@code api/parking_fuel_left/{parking}/{fuel}/{table}/{id}}
 * («Бақияи пеш аз баромад» = {@code remain_fuel_entry} последнего предыдущего ПЛ по виду топлива),
 * {@code api/parking_fuel_give/…} («Дода шавад» = {@code be_given} предыдущего) и B2B
 * {@code ref/remain_fuel}. В оригинале «предыдущий» = запись с меньшим id по тому же parking_id;
 * здесь — самая свежая запись топлива по госномеру, исключая текущий ПЛ.
 */
@Service
public class FuelPrefillService {

    /** Сколько последних записей просматриваем, чтобы отбросить строки текущего ПЛ. */
    private static final int LOOKBACK = 10;

    private final WaybillService waybillService;
    private final FuelRecordRepository fuelRecords;
    private final WaybillRepository waybills;

    public FuelPrefillService(WaybillService waybillService, FuelRecordRepository fuelRecords,
                              WaybillRepository waybills) {
        this.waybillService = waybillService;
        this.fuelRecords = fuelRecords;
        this.waybills = waybills;
    }

    /**
     * Подсказка для формы топлива.
     *
     * @param found               найдена ли предыдущая запись
     * @param fuelType            вид топлива запроса
     * @param remainBeforeExit    остаток до выезда = remain_entry предыдущей записи (может быть null)
     * @param beGiven             норма к выдаче = be_given предыдущей записи (может быть null)
     * @param sourceWaybillId     ПЛ-источник
     * @param sourceWaybillNumber номер ПЛ-источника
     * @param sourceAt            когда записана строка-источник
     */
    public record FuelPrefill(boolean found, short fuelType, BigDecimal remainBeforeExit, BigDecimal beGiven,
                              UUID sourceWaybillId, String sourceWaybillNumber, OffsetDateTime sourceAt) {
        static FuelPrefill empty(short fuelType) {
            return new FuelPrefill(false, fuelType, null, null, null, null, null);
        }
    }

    /** Для ПЛ {@code waybillId} (область доступа — как у {@link WaybillService#get}). */
    public FuelPrefill forWaybill(UUID waybillId, short fuelType) {
        Waybill wb = waybillService.get(waybillId);
        return forVehicle(wb.getVehicleRegNumber(), fuelType, wb.getId());
    }

    /** По госномеру, исключая {@code excludeWaybillId} (текущий ПЛ). */
    public FuelPrefill forVehicle(String vehicleRegNumber, short fuelType, UUID excludeWaybillId) {
        if (vehicleRegNumber == null || vehicleRegNumber.isBlank()) {
            return FuelPrefill.empty(fuelType);
        }
        List<FuelRecord> latest = fuelRecords.findLatestForVehicle(vehicleRegNumber, fuelType,
                PageRequest.of(0, LOOKBACK));
        FuelRecord prev = latest.stream()
                .filter(f -> excludeWaybillId == null || !excludeWaybillId.equals(f.getWaybillId()))
                .findFirst()
                .orElse(null);
        if (prev == null) {
            return FuelPrefill.empty(fuelType);
        }
        String number = waybills.findById(prev.getWaybillId()).map(Waybill::getNumber).orElse(null);
        return new FuelPrefill(true, fuelType, prev.getRemainEntry(), prev.getBeGiven(),
                prev.getWaybillId(), number, prev.getCreatedAt());
    }
}
