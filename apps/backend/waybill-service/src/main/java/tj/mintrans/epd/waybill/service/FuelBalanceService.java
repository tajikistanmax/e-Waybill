package tj.mintrans.epd.waybill.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.calc.WaybillCalcAssembler;
import tj.mintrans.epd.waybill.calc.model.FuelConsumption;
import tj.mintrans.epd.waybill.domain.FuelRecord;
import tj.mintrans.epd.waybill.repository.FuelRecordRepository;
import tj.mintrans.epd.waybill.repository.WaybillRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * «Бақияи пас аз даромад» — остаток топлива после возврата. В legacy вычисляется при обработке листа
 * ({@code BillNumberTrait}) и становится остатком до выезда следующего листа того же ТС
 * ({@code parkings.fuel_left_N}). Раньше у листов, оформленных в платформе, он не вычислялся вовсе:
 * графа бланка была пустой, а автоподстановка остатка следующему листу ({@link FuelPrefillService})
 * возвращала пусто.
 *
 * <p>Считается тем же расчётом, что бланк и отчёты ({@link WaybillCalcAssembler}): остаток до выезда +
 * выдано (с надбавкой ниже 0 °C) + довыдано в пути − норма. Формула legacy
 * {@code (норма + довыдача) − выдано + остаток} НЕ переносится: это ошибка знака — в боевой базе
 * 1-АД остатки уходят в минус на сотни и тысячи литров (например, −1655,456 л у листа 700179).
 * Остаток записывается в последнюю строку своего вида топлива (её и читает автоподстановка),
 * в остальных строках того же вида он очищается, чтобы суммы в отчётах не удваивались.</p>
 */
@Service
public class FuelBalanceService {

    private static final Logger log = LoggerFactory.getLogger(FuelBalanceService.class);

    private final WaybillRepository waybills;
    private final FuelRecordRepository fuelRecords;
    private final WaybillCalcAssembler assembler;

    public FuelBalanceService(WaybillRepository waybills, FuelRecordRepository fuelRecords,
                              WaybillCalcAssembler assembler) {
        this.waybills = waybills;
        this.fuelRecords = fuelRecords;
        this.assembler = assembler;
    }

    /** Пересчитать остаток после возврата; до возврата (нет одометра возврата) — ничего не делает. */
    @Transactional
    public void recompute(UUID waybillId) {
        var wb = waybills.findById(waybillId).orElse(null);
        if (wb == null || wb.getOdometerEntry() == null) {
            return;
        }
        List<FuelRecord> records = fuelRecords.findByWaybillIdOrderByCreatedAt(waybillId);
        if (records.isEmpty()) {
            return;
        }
        List<FuelConsumption> fuels;
        try {
            var view = assembler.calculate(wb, WaybillCalcAssembler.Supplement.empty());
            fuels = view.passenger() != null ? view.passenger().fuels()
                    : view.cargo() != null ? view.cargo().fuels() : List.of();
        } catch (RuntimeException e) {
            // Справочники недоступны или данных недостаточно — остаток не трогаем, возврат не блокируем.
            log.warn("Остаток топлива ПЛ {} не пересчитан: {}", waybillId, e.toString());
            return;
        }
        Map<Short, FuelRecord> lastByType = new LinkedHashMap<>();
        for (FuelRecord r : records) {
            lastByType.put(r.getFuelType(), r);
        }
        for (FuelRecord r : records) {
            FuelRecord last = lastByType.get(r.getFuelType());
            BigDecimal value = null;
            if (r == last) {
                value = fuels.stream().filter(f -> f.fuelId() == r.getFuelType()).findFirst()
                        .map(f -> BigDecimal.valueOf(f.remainEntry()).setScale(3, RoundingMode.HALF_UP))
                        .orElse(null);
            }
            if (!java.util.Objects.equals(value, r.getRemainEntry())) {
                r.setRemainEntry(value);
                fuelRecords.save(r);
            }
        }
    }

    /** Строка после пересчёта (с заполненным остатком) — для ответа API. */
    public FuelRecord reload(FuelRecord record) {
        return record == null ? null : fuelRecords.findById(record.getId()).orElse(record);
    }
}
