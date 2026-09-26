package tj.mintrans.epd.waybill.service;

import jakarta.persistence.criteria.Predicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.config.TenantScope;
import tj.mintrans.epd.waybill.domain.GpsEvent;
import tj.mintrans.epd.waybill.domain.GpsEventState;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.repository.GpsEventRepository;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.web.error.ApiErrors.FieldException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GPS-события Smart-city (MIGRATION.md 9.7 / 8.9 / 12.12 — legacy {@code GpsDataController::store},
 * {@code StoreGpsDataRequest}, {@code GpsdataCrudController}): регистрация события по госномеру с привязкой к ПЛ
 * дня (Т 1-АД), правила интервалов, журнал с фильтром по организации.
 */
@Service
public class GpsEventService {

    public record Request(String vehicleRegNumber, GpsEventState state, String direction, BigDecimal distanceKm,
                          OffsetDateTime eventTime) {
    }

    /** Ответ регистрации: событие + (для выезда из предприятия, как в legacy) маршрут/график/время выезда ПЛ. */
    public record Result(boolean success, GpsEvent event, String routeNumber, String schedule, String exitTime) {
    }

    public record Filter(String organizationRma, String vehicleRegNumber, GpsEventState state,
                         LocalDate from, LocalDate to, String q) {
    }

    public record PageResult(List<GpsEvent> content, int page, int size, long totalElements, int totalPages) {
    }

    private static final DateTimeFormatter TM = DateTimeFormatter.ofPattern("HH:mm");
    private static final int MAX_PAGE_SIZE = 500;

    private final GpsEventRepository events;
    private final WaybillRepository waybills;
    private final TenantScope tenantScope;
    private final int cooldownMinutes;
    private final Set<WaybillType> waybillTypes;

    public GpsEventService(GpsEventRepository events, WaybillRepository waybills, TenantScope tenantScope,
                           @Value("${epd.gps.event-cooldown-minutes:20}") int cooldownMinutes,
                           @Value("${epd.gps.event-waybill-types:WB_BUS,WB_TROLLEYBUS}") String waybillTypes) {
        this.events = events;
        this.waybills = waybills;
        this.tenantScope = tenantScope;
        this.cooldownMinutes = cooldownMinutes;
        this.waybillTypes = parseTypes(waybillTypes);
    }

    static Set<WaybillType> parseTypes(String csv) {
        Set<WaybillType> out = EnumSet.noneOf(WaybillType.class);
        if (csv != null) {
            Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                    .forEach(s -> out.add(WaybillType.valueOf(s.toUpperCase())));
        }
        return out.isEmpty() ? EnumSet.of(WaybillType.WB_BUS, WaybillType.WB_TROLLEYBUS) : out;
    }

    public int cooldownMinutes() {
        return cooldownMinutes;
    }

    @Transactional
    public Result register(Request req) {
        // Ошибки — с полем запроса: Smart City ждёт legacy-ответ errors: {поле: [...]} (сверка 25.09, G3).
        GpsEventRules.requestFieldError(req.state(), req.direction(), req.distanceKm())
                .ifPresent(e -> { throw new FieldException(e.getKey(), e.getValue()); });
        String reg = req.vehicleRegNumber() == null ? "" : req.vehicleRegNumber().trim().toUpperCase();
        if (reg.isEmpty()) {
            throw new FieldException("transport", "Транспортное средство не найдено.");
        }
        OffsetDateTime now = req.eventTime() != null ? req.eventTime() : OffsetDateTime.now();
        LocalDate day = now.atZoneSameInstant(ZoneId.systemDefault()).toLocalDate();
        OffsetDateTime dayStart = WaybillPeriodScan.lower(day);
        // ПЛ дня для этого ТС (legacy: последний Waybill1ad с created_at = сегодня).
        Waybill wb = waybills.findFirstByVehicleRegNumberAndWaybillTypeInAndSourceNotAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                        reg, waybillTypes, "MIGRATED", dayStart)
                .orElseThrow(() -> new FieldException("waybill", "Путевой лист для данного транспортного средства не найден."));

        String direction = req.state().isRoute() ? GpsEventRules.normalizeDirection(req.direction()) : null;
        OffsetDateTime lastSame = (direction != null
                ? events.findFirstByVehicleRegNumberAndStateAndDirectionAndEventTimeGreaterThanEqualOrderByEventTimeDesc(reg, req.state(), direction, dayStart)
                : events.findFirstByVehicleRegNumberAndStateAndEventTimeGreaterThanEqualOrderByEventTimeDesc(reg, req.state(), dayStart))
                .map(GpsEvent::getEventTime).orElse(null);
        OffsetDateTime lastEnter = req.state() == GpsEventState.EXIT_FROM_ROUTE
                ? events.findFirstByVehicleRegNumberAndStateAndDirectionOrderByEventTimeDesc(reg, GpsEventState.ENTER_INTO_ROUTE, direction)
                        .map(GpsEvent::getEventTime).orElse(null)
                : null;
        GpsEventRules.cooldownError(req.state(), now, lastSame, lastEnter, cooldownMinutes)
                .ifPresent(m -> { throw new FieldException("state", m); });

        GpsEvent e = new GpsEvent();
        e.setWaybillId(wb.getId());
        e.setOrganizationRma(wb.getOrganizationRma());
        e.setOrganizationName(snapshot(wb.getOrganizationSnapshot(), "name"));
        e.setVehicleRegNumber(reg);
        e.setDriverRma(wb.getDriverRma());
        e.setDriverName(snapshot(wb.getDriverSnapshot(), "fullName"));
        e.setRoute(wb.getRoute());
        e.setState(req.state());
        e.setDirection(direction);
        e.setDistanceKm(req.distanceKm());
        e.setEventTime(now);
        GpsEvent saved = events.save(e);

        if (req.state() == GpsEventState.EXIT_FROM_COMPANY) {
            String exitTime = wb.getValidFrom() != null
                    ? TM.format(wb.getValidFrom().atZoneSameInstant(ZoneId.systemDefault())) : null;
            return new Result(true, saved, wb.getRoute(), wb.getSchedule(), exitTime);
        }
        return new Result(true, saved, null, null, null);
    }

    /** Журнал событий (legacy admin/gpsevent): тенант — своя область организаций; платформенные роли — все. */
    @Transactional(readOnly = true)
    public PageResult list(Filter f, int page, int size) {
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Set<String> scope = tenantScope.isBounded() ? tenantScope.rmas() : null;
        if (scope != null && (scope.isEmpty() || scope.contains("__none__"))) {
            return new PageResult(List.of(), p, s, 0, 0);
        }
        Specification<GpsEvent> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (scope != null) {
                ps.add(root.get("organizationRma").in(scope));
            }
            if (f.organizationRma() != null && !f.organizationRma().isBlank()) {
                ps.add(cb.equal(root.get("organizationRma"), f.organizationRma().trim()));
            }
            if (f.vehicleRegNumber() != null && !f.vehicleRegNumber().isBlank()) {
                ps.add(cb.equal(root.get("vehicleRegNumber"), f.vehicleRegNumber().trim().toUpperCase()));
            }
            if (f.state() != null) {
                ps.add(cb.equal(root.get("state"), f.state()));
            }
            if (f.from() != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("eventTime"), WaybillPeriodScan.lower(f.from())));
            }
            if (f.to() != null) {
                ps.add(cb.lessThan(root.get("eventTime"), WaybillPeriodScan.upper(f.to())));
            }
            if (f.q() != null && !f.q().isBlank()) {
                String pattern = "%" + f.q().trim().toLowerCase() + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("vehicleRegNumber")), pattern),
                        cb.like(cb.lower(cb.coalesce(root.get("driverName"), "")), pattern),
                        cb.like(cb.lower(cb.coalesce(root.get("organizationName"), "")), pattern),
                        cb.like(cb.lower(cb.coalesce(root.get("route"), "")), pattern)));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
        Page<GpsEvent> pg = events.findAll(spec, PageRequest.of(p, s, Sort.by(Sort.Direction.DESC, "eventTime")));
        return new PageResult(pg.getContent(), p, s, pg.getTotalElements(), pg.getTotalPages());
    }

    private static String snapshot(Map<String, Object> snap, String key) {
        if (snap == null || snap.get(key) == null) {
            return null;
        }
        return String.valueOf(snap.get(key));
    }
}
