package tj.mintrans.epd.waybill.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.client.MasterDataClient;
import tj.mintrans.epd.waybill.config.CurrentUser;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillRequest;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.repository.WaybillRequestRepository;
import tj.mintrans.epd.waybill.web.error.ApiErrors.ConflictException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.ForbiddenException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Заявки на путевой лист (driver-initiated): водитель подаёт → диспетчер проверяет/исправляет →
 * одобряет (создаётся ПЛ) или отклоняет. Приватность водителя: он вписывает госномер, сервер
 * валидирует принадлежность ТС его организации, НЕ раскрывая списки; личность — из токена.
 */
@Service
public class WaybillRequestService {

    private final WaybillRequestRepository requests;
    private final WaybillRepository waybills;
    private final WaybillService waybillService;
    private final MasterDataClient masterData;
    private final CurrentUser currentUser;

    public WaybillRequestService(WaybillRequestRepository requests, WaybillRepository waybills,
                                 WaybillService waybillService, MasterDataClient masterData, CurrentUser currentUser) {
        this.requests = requests;
        this.waybills = waybills;
        this.waybillService = waybillService;
        this.masterData = masterData;
        this.currentUser = currentUser;
    }

    /**
     * Водитель подаёт заявку. Личность (РМА/ФИО) — из токена (не из формы). Госномер водитель
     * вписывает вручную; сервер валидирует, что ТС принадлежит ЕГО организации (без раскрытия списка).
     */
    @Transactional
    public WaybillRequest create(WaybillType type, String vehicleRegNumber, LocalDate requestedFrom,
                                 Integer odometer, String communicationType, String route,
                                 String schedule, String notes) {
        String driverRma = currentUser.rma()
                .orElseThrow(() -> new ForbiddenException("Не удалось определить водителя из входа"));
        // «Одна заявка в работе»: пока прошлая заявка водителя ещё не рассмотрена (PENDING),
        // подать новую нельзя — иначе диспетчер получил бы дубли на один и тот же выезд.
        if (requests.existsByDriverRmaAndStatus(driverRma, WaybillRequest.PENDING)) {
            throw new ConflictException(
                    "У вас уже есть заявка в статусе «Ожидает» — дождитесь её рассмотрения диспетчером "
                            + "или отмените её, прежде чем подавать новую.");
        }
        // Задним числом заявку подать нельзя: дата выхода не может быть в прошлом (по времени РТ).
        if (requestedFrom != null && requestedFrom.isBefore(LocalDate.now(ZoneId.of("Asia/Dushanbe")))) {
            throw new UnprocessableException("Дата выхода не может быть в прошлом — задним числом заявку подать нельзя.");
        }
        String orgRma = currentUser.organizationRma()
                .orElseThrow(() -> new ForbiddenException("Не удалось определить организацию водителя"));
        var org = masterData.findOrganization(orgRma)
                .orElseThrow(() -> new NotFoundException("Организация не найдена"));
        var driver = masterData.findDriver(driverRma)
                .orElseThrow(() -> new NotFoundException("Водитель не найден"));
        // ТС: существует и принадлежит организации водителя (без раскрытия списка ТС фирмы).
        String reg = vehicleRegNumber == null ? "" : vehicleRegNumber.trim();
        var vehicle = masterData.findVehicle(reg)
                .filter(v -> str(org.get("id")).equals(str(v.get("organizationId"))))
                .orElseThrow(() -> new UnprocessableException(
                        "ТС с госномером «%s» не найдено в вашей организации".formatted(reg)));

        var req = new WaybillRequest();
        req.setOrganizationRma(orgRma);
        req.setDriverRma(driverRma);
        req.setDriverName(str(driver.get("fullName")));
        req.setVehicleRegNumber(str(vehicle.get("registrationNumber")));
        req.setWaybillType(type);
        req.setRequestedFrom(requestedFrom);
        req.setOdometer(odometer);
        req.setCommunicationType(communicationType);
        req.setRoute(route);
        req.setSchedule(schedule);
        req.setNotes(notes);
        req.setStatus(WaybillRequest.PENDING);
        return requests.save(req);
    }

    /** Заявки текущего водителя (свои). */
    public List<WaybillRequest> listForDriver() {
        return currentUser.rma()
                .map(requests::findByDriverRmaOrderByCreatedAtDesc)
                .orElseGet(List::of);
    }

    /** Заявки организации диспетчера (пусто без организации; по умолчанию — все, можно фильтр по статусу). */
    public List<WaybillRequest> listForOrganization(String status) {
        String orgRma = currentUser.organizationRma().orElse(null);
        if (orgRma == null) {
            return List.of();
        }
        return (status == null || status.isBlank())
                ? requests.findByOrganizationRmaOrderByCreatedAtDesc(orgRma)
                : requests.findByOrganizationRmaAndStatusOrderByCreatedAtAsc(orgRma, status);
    }

    /** Заявка с тенант-проверкой (не-админ видит только заявки своей организации; иначе 404). */
    private WaybillRequest getScoped(UUID id) {
        var req = requests.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Заявка не найдена"));
        if (currentUser.isTenantScoped()
                && !currentUser.organizationRma().map(o -> o.equals(req.getOrganizationRma())).orElse(false)) {
            throw new NotFoundException("Заявка не найдена");
        }
        return req;
    }

    /** Диспетчер исправляет поля заявки (пока она «Ожидает»). */
    @Transactional
    public WaybillRequest edit(UUID id, WaybillType type, String vehicleRegNumber, LocalDate requestedFrom,
                               Integer odometer, String communicationType, String route, String schedule, String notes) {
        var req = getScoped(id);
        requirePending(req, "Изменять");
        if (type != null) req.setWaybillType(type);
        if (vehicleRegNumber != null && !vehicleRegNumber.isBlank()) req.setVehicleRegNumber(vehicleRegNumber.trim());
        if (requestedFrom != null) req.setRequestedFrom(requestedFrom);
        if (odometer != null) req.setOdometer(odometer);
        if (communicationType != null) req.setCommunicationType(communicationType);
        if (route != null) req.setRoute(route);
        if (schedule != null) req.setSchedule(schedule);
        if (notes != null) req.setNotes(notes);
        return requests.save(req);
    }

    /**
     * Диспетчер одобряет заявку → создаётся путевой лист (Т1). «Один поток»: если у водителя есть
     * ПЛ на линии (ACTIVE) — сначала закрываем его возвратом (Т5) по одометру заявки (выходит из
     * OPEN_STATUSES), затем создаётся новый ПЛ. Всё в ОДНОЙ транзакции — атомарно (если создание
     * нового не проходит проверки, возврат старого откатывается). typeData/communicationType
     * диспетчер дополняет, если тип ПЛ требует (2-Б/3-С/международные и т.п.).
     */
    @Transactional
    public WaybillRequest approve(UUID id, String communicationType, Map<String, Object> typeData) {
        var req = getScoped(id);
        requirePending(req, "Одобрить");
        String dispatcherRma = currentUser.rma()
                .orElseThrow(() -> new UnprocessableException("У диспетчера не задан РМА в токене"));

        // Освободить водителя от прошлого ПЛ (правило «один активный ПЛ на водителя/ТС»).
        for (Waybill old : waybills.findByDriverRmaAndStatusIn(req.getDriverRma(), WaybillStatus.OPEN_STATUSES)) {
            if (old.getStatus() == WaybillStatus.ACTIVE) {
                if (req.getOdometer() == null) {
                    throw new UnprocessableException("Укажите одометр — у водителя есть рейс на линии, его нужно закрыть");
                }
                waybillService.returnTrip(old.getId(), dispatcherRma, req.getOdometer()); // Т5 → RETURNED (вне OPEN)
            } else {
                throw new ConflictException(
                        "У водителя есть незавершённый путевой лист (статус %s) — завершите его перед выдачей нового"
                                .formatted(old.getStatus()));
            }
        }

        String comm = communicationType != null ? communicationType : req.getCommunicationType();
        var wb = waybillService.create(req.getWaybillType(), req.getOrganizationRma(), req.getVehicleRegNumber(),
                req.getDriverRma(), null, comm, req.getRoute(), req.getSchedule(), null, typeData);
        OffsetDateTime from = req.getRequestedFrom() != null
                ? req.getRequestedFrom().atStartOfDay().atOffset(ZoneOffset.UTC) : OffsetDateTime.now();
        waybillService.signT1(wb.getId(), dispatcherRma, from, null);

        req.setStatus(WaybillRequest.APPROVED);
        req.setWaybillId(wb.getId());
        req.setReviewedBy(currentUser.username().orElse(dispatcherRma));
        req.setReviewedAt(OffsetDateTime.now());
        return requests.save(req);
    }

    /** Диспетчер отклоняет заявку с причиной (водитель увидит причину). */
    @Transactional
    public WaybillRequest reject(UUID id, String reason) {
        var req = getScoped(id);
        requirePending(req, "Отклонить");
        req.setStatus(WaybillRequest.REJECTED);
        req.setRejectReason(reason);
        req.setReviewedBy(currentUser.username().orElse(""));
        req.setReviewedAt(OffsetDateTime.now());
        return requests.save(req);
    }

    /** Водитель отменяет СВОЮ заявку (пока «Ожидает»). */
    @Transactional
    public WaybillRequest cancelOwn(UUID id) {
        var req = getScoped(id);
        if (!currentUser.rma().map(r -> r.equals(req.getDriverRma())).orElse(false)) {
            throw new ForbiddenException("Можно отменить только свою заявку");
        }
        requirePending(req, "Отменить");
        req.setStatus(WaybillRequest.CANCELLED);
        return requests.save(req);
    }

    private static void requirePending(WaybillRequest req, String action) {
        if (!WaybillRequest.PENDING.equals(req.getStatus())) {
            throw new ConflictException("%s можно только заявку в статусе «Ожидает»".formatted(action));
        }
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }
}
