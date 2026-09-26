package tj.mintrans.epd.waybill.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.client.MasterDataClient;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.domain.WaybillStatusEvent;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.repository.WaybillStatusEventRepository;
import tj.mintrans.epd.waybill.web.error.ApiErrors.ConflictException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.FieldException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Legacy-совместимый поток агрегаторов такси (ЧУРА/Jura, НЕРУ/Neru) —
 * spec/notes/01-legacy-api-и-формы.md, раздел 7.
 * Отличие от портального потока: новый запрос аннулирует все действующие ПЛ
 * на это ТС/этого водителя, блокирующие проверки лицензий/сроков — те же.
 */
@Service
public class AggregatorService {

    /**
     * Максимальная длительность рейса агрегатора от даты выезда. Поднято с legacy-лимита
     * в 7 дней до 30 — теперь совпадает с потолком долгой таксомоторной заявки «3с30»
     * (legacy waybill_neru) и с уже действующим для WB_TRUCK_INTL/WB_PAX_INTL правилом:
     * жёсткий предел как осознанный предохранитель цифровой платформы, а не открытый
     * «1 рейс» из бумажного оригинала.
     */
    private static final int MAX_TRIP_DAYS = 30;

    /** «Действующие» статусы, при которых ПЛ пригоден к использованию (для legacy GET агрегатора). */
    private static final Set<WaybillStatus> USABLE =
            EnumSet.of(WaybillStatus.READY, WaybillStatus.ISSUED, WaybillStatus.ACTIVE);

    static final String ACTOR = "aggregator";

    private final WaybillRepository waybills;
    private final WaybillStatusEventRepository events;
    private final MasterDataClient masterData;
    private final WaybillService waybillService;

    public AggregatorService(WaybillRepository waybills,
                             WaybillStatusEventRepository events,
                             MasterDataClient masterData,
                             WaybillService waybillService) {
        this.waybills = waybills;
        this.events = events;
        this.masterData = masterData;
        this.waybillService = waybillService;
    }

    /** Итог запроса агрегатора: действующий лист этой тройки (200) или новая заявка (201). */
    public record Submission(Waybill waybill, boolean created) {
    }

    /**
     * Запрос агрегатора с семантикой legacy {@code Waybill3cController::waybill_neru} (сверка 25.09, G3).
     *
     * <ul>
     *   <li>Есть лист этой организации, ТС и водителя, и сейчас между днём выезда и днём въезда —
     *       он и возвращается: 409 «Доктор/Механик не подтвердил путёвку», пока нет осмотров,
     *       затем 200 с листом. Раньше каждый повторный запрос аннулировал заявку и создавал новую,
     *       и лист, опрашиваемый агрегатором, не доживал до осмотров.</li>
     *   <li>Иначе дата выезда должна быть сегодняшней (422 {@code exit_date}), и создаётся новая
     *       заявка — прежние агрегаторские листы на ТС и водителя закрываются.</li>
     * </ul>
     */
    @Transactional
    public Submission submit(String organizationRma, String transportRegistrationNumber, String driverRma,
                             String employeeRma, OffsetDateTime exitDate, OffsetDateTime entryDate, int distance) {
        validateDates(exitDate, entryDate);
        var org = masterData.findOrganization(organizationRma)
                .orElseThrow(() -> new NotFoundException("Organization not found"));
        masterData.findVehicle(transportRegistrationNumber)
                .orElseThrow(() -> new NotFoundException("Transport not found"));
        masterData.findDriver(driverRma)
                .orElseThrow(() -> new NotFoundException("Driver not found"));

        var now = OffsetDateTime.now();
        var current = waybills.findFirstByOrganizationRmaAndVehicleRegNumberAndDriverRmaAndSourceAndStatusInOrderByCreatedAtDesc(
                organizationRma, transportRegistrationNumber, driverRma, "AGGREGATOR", WaybillStatus.OPEN_STATUSES);
        if (current.isPresent() && inForce(current.get(), now)) {
            return new Submission(requireConfirmed(current.get()), false);
        }
        if (!day(exitDate).equals(day(now))) {
            throw new FieldException("exit_date", "Дата выезда должна быть сегодняшней");
        }
        return new Submission(create(organizationRma, transportRegistrationNumber, driverRma, employeeRma,
                exitDate, entryDate, distance, org), true);
    }

    /** Лист «в силе», как в legacy: сейчас не раньше дня выезда и не позже дня въезда. */
    static boolean inForce(Waybill wb, OffsetDateTime now) {
        if (wb.getValidFrom() == null || wb.getValidTo() == null) {
            return false;
        }
        LocalDate today = day(now);
        return !today.isBefore(day(wb.getValidFrom())) && !today.isAfter(day(wb.getValidTo()));
    }

    private static LocalDate day(OffsetDateTime t) {
        return t.atZoneSameInstant(ZoneId.systemDefault()).toLocalDate();
    }

    /** Правила дат legacy {@code StoreWaybill3cNeruRequest}; поле ошибки — для ответа {@code errors}. */
    private static void validateDates(OffsetDateTime exitDate, OffsetDateTime entryDate) {
        if (exitDate == null) {
            throw new FieldException("exit_date", "Параметр exit_date обязателен.");
        }
        if (entryDate != null) {
            if (!entryDate.isAfter(exitDate)) {
                throw new FieldException("entry_date", "Дата въезда должна быть больше даты выезда");
            }
            if (day(entryDate).isAfter(day(exitDate).plusDays(MAX_TRIP_DAYS - 1))) {
                throw new FieldException("entry_date",
                        "Дата въезда не может превышать дату выезда более чем на %d дней".formatted(MAX_TRIP_DAYS));
            }
        }
    }

    /**
     * Заявка агрегатора: аннулирует действующие ПЛ на ТС/водителя и создаёт новый
     * WB_TAXI (source = AGGREGATOR) со статусом CREATED — «Ожидает» подтверждений
     * врача и механика.
     */
    @Transactional
    public Waybill create(String organizationRma, String transportRegistrationNumber, String driverRma,
                          String employeeRma, OffsetDateTime exitDate, OffsetDateTime entryDate, int distance) {
        validateDates(exitDate, entryDate);
        var org = masterData.findOrganization(organizationRma)
                .orElseThrow(() -> new NotFoundException("Organization not found"));
        return create(organizationRma, transportRegistrationNumber, driverRma, employeeRma, exitDate, entryDate,
                distance, org);
    }

    private Waybill create(String organizationRma, String transportRegistrationNumber, String driverRma,
                           String employeeRma, OffsetDateTime exitDate, OffsetDateTime entryDate, int distance,
                           Map<String, Object> org) {
        // Дата выезда в разумном окне (защита от backdating/датирования далёким будущим).
        var now = OffsetDateTime.now();
        if (exitDate.isBefore(now.minusDays(1)) || exitDate.isAfter(now.plusDays(MAX_TRIP_DAYS))) {
            throw new FieldException("exit_date", "Дата выезда вне допустимого окна (не в далёком прошлом/будущем)");
        }
        var vehicle = masterData.findVehicle(transportRegistrationNumber)
                .orElseThrow(() -> new NotFoundException("Transport not found"));
        var driver = masterData.findDriver(driverRma)
                .orElseThrow(() -> new NotFoundException("Driver not found"));

        // Legacy-семантика: новый запрос закрывает предыдущие действующие ПЛ (ТС или водитель)
        cancelOpenWaybills(transportRegistrationNumber, driverRma);

        // Лицензии/сроки/принадлежность — те же блокирующие проверки, что и в портальном потоке
        waybillService.runBlockingChecks(org, driver, vehicle);

        int plannedOdometerExit = intOrZero(vehicle.get("odometer"));
        int plannedOdometerEntry = plannedOdometerExit + distance;

        var wb = new Waybill();
        wb.setWaybillType(WaybillType.WB_TAXI);
        wb.setOrganizationRma(organizationRma);
        wb.setVehicleRegNumber(transportRegistrationNumber);
        wb.setDriverRma(driverRma);
        wb.setSource("AGGREGATOR");
        wb.setValidFrom(exitDate);
        wb.setValidTo(entryDate != null ? entryDate : exitDate.plusDays(MAX_TRIP_DAYS));
        // Снимки мастер-данных на момент оформления (принцип иммутабельности, раздел 11.1)
        wb.setOrganizationSnapshot(org);
        wb.setVehicleSnapshot(vehicle);
        wb.setDriverSnapshot(driver);
        // Плановый пробег храним как отметку — фактический одометр фиксируют Т4/Т5
        wb.setSpecialMark("Агрегатор: плановый одометр выезда %d, плановый одометр возврата %d (дистанция %d км)"
                .formatted(plannedOdometerExit, plannedOdometerEntry, distance));
        var saved = waybills.save(wb);
        events.save(WaybillStatusEvent.of(saved.getId(), null, WaybillStatus.DRAFT, ACTOR, "Заявка агрегатора"));

        var employee = employeeRma == null ? null : masterData.findEmployee(employeeRma).orElse(null);
        // Т1 атрибутируется только диспетчеру ЭТОЙ организации; чужой/неизвестный/не-диспетчер —
        // как и прежде, мягкий фолбэк на общую ветку (legacy-толерантность: заявка не отклоняется).
        if (employee != null && Integer.valueOf(3).equals(intOrNull(employee.get("type")))
                && str(org.get("id")).equals(str(employee.get("organizationId")))) {
            // employee_rma — диспетчер: Т1 подписывается им
            saved.setDispatcherRma(employeeRma);
            var t1 = new LinkedHashMap<String, Object>();
            t1.put("validFrom", exitDate.toString());
            t1.put("validTo", saved.getValidTo().toString());
            t1.put("plannedOdometerExit", plannedOdometerExit);
            t1.put("plannedOdometerEntry", plannedOdometerEntry);
            t1.put("distance", distance);
            t1.put("dispatcher", employee.get("name"));
            waybillService.addTitle(saved, "T1", employeeRma, "DISPATCHER", t1);
            waybillService.transition(saved, WaybillStatus.CREATED, employeeRma, "Т1 подписан (заявка агрегатора)");
        } else {
            waybillService.transition(saved, WaybillStatus.CREATED, ACTOR, "Заявка агрегатора принята");
        }
        return waybills.save(saved);
    }

    /** Legacy GET: отдаёт только подтверждённый врачом и механиком ПЛ. */
    public Waybill getConfirmed(UUID id) {
        return requireConfirmed(waybillService.get(id)); // 404 «Путевой лист не найден»
    }

    private static Waybill requireConfirmed(Waybill wb) {
        if (!wb.isMedPassed()) {
            throw new ConflictException("Доктор не подтвердил путёвку");
        }
        if (!wb.isTechPassed()) {
            throw new ConflictException("Механик не подтвердил путёвку");
        }
        // Статус-гейт: заблокированный/аннулированный/просроченный/закрытый ПЛ НЕ отдаём как
        // действующий (флаги med/tech защёлкиваются и не сбрасываются при block/cancel/close —
        // иначе агрегатор считал бы «Активный» даже для ПЛ, снятого инспектором).
        if (!USABLE.contains(wb.getStatus())) {
            throw new ConflictException("Путевой лист не в действующем статусе (%s)".formatted(wb.getStatus()));
        }
        return wb;
    }

    private void cancelOpenWaybills(String vehicleRegNumber, String driverRma) {
        var open = new LinkedHashSet<Waybill>();
        open.addAll(waybills.findByVehicleRegNumberAndStatusIn(vehicleRegNumber, WaybillStatus.OPEN_STATUSES));
        open.addAll(waybills.findByDriverRmaAndStatusIn(driverRma, WaybillStatus.OPEN_STATUSES));
        for (var wb : open) {
            // Только СВОИ (агрегаторские) ПЛ: агрегатор не вправе молча аннулировать портальный
            // госдокумент — активный портальный ПЛ заблокирует создание (runBlockingChecks).
            if ("AGGREGATOR".equals(wb.getSource())) {
                waybillService.cancel(wb.getId(), "Закрыт по новому запросу агрегатора", ACTOR);
            }
        }
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    private static Integer intOrNull(Object o) {
        return o instanceof Number n ? n.intValue() : o != null ? Integer.valueOf(o.toString()) : null;
    }

    private static int intOrZero(Object o) {
        Integer i = intOrNull(o);
        return i == null ? 0 : i;
    }
}
