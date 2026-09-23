package tj.mintrans.epd.waybill.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.config.CurrentUser;
import tj.mintrans.epd.waybill.domain.Malumotnoma;
import tj.mintrans.epd.waybill.domain.MalumotnomaLine;
import tj.mintrans.epd.waybill.domain.MalumotnomaRoute;
import tj.mintrans.epd.waybill.repository.MalumotnomaRepository;
import tj.mintrans.epd.waybill.repository.MalumotnomaRouteRepository;
import tj.mintrans.epd.waybill.web.error.ApiErrors.ForbiddenException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Справки пассажирам (маълумотнома): выдача, просмотр, отчёт по кассирам.
 *
 * <p>Формула цены — {@code docs/spec/07 §7}: по каждой строке маршрута
 * {@code цена_вида × (2 если туда-обратно)}, сумма, затем {@code ×0.5} для льготной
 * справки ({@code age = 1}). Мультиарендность: тенант видит и выдаёт только свои справки.</p>
 */
@Service
public class MalumotnomaService {

    private static final Map<Short, String> TYPE_NAME = Map.of(
            (short) 1, "Автобус", (short) 3, "Микроавтобус", (short) 4, "Легковой автомобиль");

    private final MalumotnomaRepository malumotnomas;
    private final MalumotnomaRouteRepository routes;
    private final CurrentUser currentUser;
    private final tj.mintrans.epd.waybill.config.TenantScope tenantScope;

    public MalumotnomaService(MalumotnomaRepository malumotnomas, MalumotnomaRouteRepository routes,
                              CurrentUser currentUser, tj.mintrans.epd.waybill.config.TenantScope tenantScope) {
        this.malumotnomas = malumotnomas;
        this.routes = routes;
        this.currentUser = currentUser;
        this.tenantScope = tenantScope;
    }

    public record LineRequest(UUID routeId, boolean roundTrip) {
    }

    public record CreateRequest(String fio, Short transportTypeId, Short age, List<LineRequest> lines) {
    }

    // ------------------------------------------------------------- справки

    @Transactional(readOnly = true)
    public List<Malumotnoma> list() {
        if (currentUser.isTenantScoped()) {
            var scope = tenantScope.rmas();
            return scope.isEmpty() || scope.contains("__none__")
                    ? List.of()
                    : malumotnomas.findByOrganizationRmaInOrderByCreatedAtDesc(scope);
        }
        return malumotnomas.findAll(org("createdAt"));
    }

    @Transactional(readOnly = true)
    public Malumotnoma get(UUID id) {
        Malumotnoma m = malumotnomas.findById(id)
                .orElseThrow(() -> new NotFoundException("Справка не найдена"));
        assertVisible(m);
        return m;
    }

    @Transactional
    public Malumotnoma create(CreateRequest req) {
        if (req.fio() == null || req.fio().isBlank()) {
            throw new UnprocessableException("Укажите ФИО получателя справки");
        }
        short type = req.transportTypeId() == null ? 0 : req.transportTypeId();
        if (!TYPE_NAME.containsKey(type)) {
            throw new UnprocessableException("Вид транспорта справки: 1 (автобус), 3 (микроавтобус) или 4 (легковой)");
        }
        if (req.lines() == null || req.lines().isEmpty()) {
            throw new UnprocessableException("Добавьте хотя бы один маршрут");
        }
        short age = req.age() != null && req.age() == 1 ? (short) 1 : (short) 0;

        Malumotnoma m = new Malumotnoma();
        m.setFio(req.fio().trim());
        m.setTransportTypeId(type);
        m.setAge(age);
        m.setOrganizationRma(currentUser.organizationRma().orElse(null));
        m.setIssuerRma(currentUser.rma().orElse(null));
        // «Выдал» на справке и «Кассир» в отчёте — Ф.И.О. кассира, а не логин (было «accountant»).
        m.setIssuerName(currentUser.fullName().filter(s -> !s.isBlank())
                .or(currentUser::username).orElse(null));

        BigDecimal sum = BigDecimal.ZERO;
        List<String> summary = new ArrayList<>();
        for (LineRequest lr : req.lines()) {
            MalumotnomaRoute route = routes.findById(lr.routeId())
                    .orElseThrow(() -> new UnprocessableException("Маршрут справки не найден"));
            MalumotnomaLine line = new MalumotnomaLine();
            line.setRoute(route);
            line.setRoundTrip(lr.roundTrip());
            m.addLine(line);

            BigDecimal linePrice = route.priceFor(type);
            if (lr.roundTrip()) {
                linePrice = linePrice.multiply(BigDecimal.valueOf(2));
            }
            sum = sum.add(linePrice);
            summary.add(route.getName() + (lr.roundTrip() ? " (сафари рафту баргашт)" : ""));
        }
        if (age == 1) {
            sum = sum.multiply(BigDecimal.valueOf(0.5));
        }
        m.setPrice(sum.setScale(2, RoundingMode.HALF_UP));
        m.setRouteSummary(String.join(", ", summary));
        return malumotnomas.save(m);
    }

    @Transactional
    public void delete(UUID id) {
        Malumotnoma m = get(id);
        malumotnomas.delete(m);
    }

    // ------------------------------------------------------------- отчёт

    public record ReportRow(UUID id, String fio, String transportType, boolean privileged,
                            OffsetDateTime issuedAt, String routes, BigDecimal price,
                            String issuerName, String updaterName) {
    }

    public record CashierGroup(String issuerRma, String issuerName, long count, BigDecimal amount,
                               List<ReportRow> items) {
    }

    public record Report(LocalDate from, LocalDate to, long count, BigDecimal total,
                         List<CashierGroup> groups) {
    }

    @Transactional(readOnly = true)
    public Report report(LocalDate from, LocalDate to, String requestedIssuer) {
        if (from.isAfter(to)) {
            throw new UnprocessableException("Начало периода позже его окончания");
        }
        java.util.Set<String> orgs;
        String issuer;
        if (currentUser.isTenantScoped()) {
            orgs = tenantScope.rmas();
            // Тенант без роли администратора компании/филиала видит только собственные справки.
            issuer = currentUser.hasRole("COMPANY_ADMIN") || currentUser.hasRole("BRANCH_ADMIN")
                    || currentUser.hasRole("ACCOUNTANT")
                    ? emptyToNull(requestedIssuer)
                    : currentUser.rma().orElse("__none__");
        } else {
            orgs = null;
            issuer = emptyToNull(requestedIssuer);
        }

        OffsetDateTime fromTs = from.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime toTs = to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        List<Malumotnoma> list = orgs == null
                ? malumotnomas.forReport(fromTs, toTs, issuer)
                : malumotnomas.forReportScoped(fromTs, toTs, orgs, issuer);

        Map<String, List<ReportRow>> byCashier = new LinkedHashMap<>();
        Map<String, String> cashierName = new LinkedHashMap<>();
        for (Malumotnoma m : list) {
            String key = m.getIssuerRma() != null ? m.getIssuerRma()
                    : (m.getIssuerName() != null ? m.getIssuerName() : "—");
            cashierName.putIfAbsent(key, m.getIssuerName() != null ? m.getIssuerName() : key);
            byCashier.computeIfAbsent(key, k -> new ArrayList<>()).add(new ReportRow(
                    m.getId(), m.getFio(), TYPE_NAME.getOrDefault(m.getTransportTypeId(), "—"),
                    m.getAge() == 1, m.getCreatedAt(),
                    m.getRouteSummary() == null ? "" : m.getRouteSummary(),
                    m.getPrice(), m.getIssuerName(), m.getUpdaterName()));
        }

        List<CashierGroup> groups = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        long count = 0;
        for (Map.Entry<String, List<ReportRow>> e : byCashier.entrySet()) {
            BigDecimal amount = e.getValue().stream()
                    .map(ReportRow::price).reduce(BigDecimal.ZERO, BigDecimal::add);
            groups.add(new CashierGroup(e.getKey(), cashierName.get(e.getKey()),
                    e.getValue().size(), amount, e.getValue()));
            total = total.add(amount);
            count += e.getValue().size();
        }
        return new Report(from, to, count, total, groups);
    }

    // ------------------------------------------------------------- маршруты

    @Transactional(readOnly = true)
    public List<MalumotnomaRoute> routes(boolean all) {
        return all ? routes.findAllByOrderByName() : routes.findByActiveTrueOrderByName();
    }

    public record RouteRequest(String name, Double distanceKm, BigDecimal carPrice,
                               BigDecimal mbusPrice, BigDecimal busPrice, Boolean active) {
    }

    @Transactional
    public MalumotnomaRoute saveRoute(UUID id, RouteRequest req) {
        requirePlatformAdmin();
        MalumotnomaRoute r = id == null ? new MalumotnomaRoute()
                : routes.findById(id).orElseThrow(() -> new NotFoundException("Маршрут не найден"));
        if (req.name() == null || req.name().isBlank()) {
            throw new UnprocessableException("Укажите название маршрута");
        }
        r.setName(req.name().trim());
        r.setDistanceKm(req.distanceKm() == null ? 0 : req.distanceKm());
        r.setCarPrice(nz(req.carPrice()));
        r.setMbusPrice(nz(req.mbusPrice()));
        r.setBusPrice(nz(req.busPrice()));
        r.setActive(req.active() == null || req.active());
        return routes.save(r);
    }

    @Transactional
    public void deleteRoute(UUID id) {
        requirePlatformAdmin();
        if (!routes.existsById(id)) {
            throw new NotFoundException("Маршрут не найден");
        }
        routes.deleteById(id);
    }

    // ------------------------------------------------------------- прочее

    private void assertVisible(Malumotnoma m) {
        if (currentUser.isTenantScoped() && !tenantScope.contains(m.getOrganizationRma())) {
            throw new NotFoundException("Справка не найдена");
        }
    }

    private void requirePlatformAdmin() {
        if (!currentUser.hasRole("SYSTEM_ADMIN")) {
            throw new ForbiddenException("Справочник маршрутов справок правит только администратор системы");
        }
    }

    private static org.springframework.data.domain.Sort org(String field) {
        return org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, field);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String emptyToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
