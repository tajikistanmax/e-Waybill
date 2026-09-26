package tj.mintrans.epd.waybill.service;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.config.CurrentUser;
import tj.mintrans.epd.waybill.domain.Malumotnoma;
import tj.mintrans.epd.waybill.domain.MalumotnomaLine;
import tj.mintrans.epd.waybill.domain.MalumotnomaRoute;
import tj.mintrans.epd.waybill.print.PrintZone;
import tj.mintrans.epd.waybill.repository.MalumotnomaRepository;
import tj.mintrans.epd.waybill.repository.MalumotnomaRouteRepository;
import tj.mintrans.epd.waybill.web.error.ApiErrors.ForbiddenException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Справки пассажирам (маълумотнома): выдача, правка, аннулирование, реестр, отчёт по кассирам.
 *
 * <p>Формула цены — {@code docs/spec/07 §7}: по каждой строке маршрута
 * {@code цена_вида × (2 если туда-обратно)}, сумма, затем {@code ×0.5} для льготной
 * справки ({@code age = 1}). Мультиарендность: тенант видит и выдаёт только свои справки.</p>
 *
 * <p>Как в «Роҳхат» (сверка 25.09, E5): у справки сквозной номер, удалить её нельзя (ошибочную
 * аннулирует администратор — номер остаётся в журнале), при правке меняются вид транспорта,
 * льгота и маршруты, Ф.И.О. — нет.</p>
 */
@Service
public class MalumotnomaService {

    private static final Map<Short, String> TYPE_NAME = Map.of(
            (short) 1, "Автобус", (short) 3, "Микроавтобус", (short) 4, "Легковой автомобиль");
    private static final int MAX_PAGE_SIZE = 200;

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

    /** Правка выданной справки: как в «Роҳхат» — вид транспорта, льгота, маршруты; Ф.И.О. не меняется. */
    public record UpdateRequest(Short transportTypeId, Short age, List<LineRequest> lines) {
    }

    public record AnnulRequest(String reason) {
    }

    public record PageResult(List<Malumotnoma> content, int page, int size, long totalElements, int totalPages) {
    }

    // ------------------------------------------------------------- справки

    /**
     * Реестр справок: поиск по номеру или Ф.И.О. (как в «Роҳхат»), период выдачи (время Душанбе),
     * постранично — с архивом «Роҳхат» справок десятки тысяч.
     */
    @Transactional(readOnly = true)
    public PageResult list(String q, LocalDate from, LocalDate to, int page, int size) {
        int sz = Math.max(1, Math.min(size <= 0 ? 20 : size, MAX_PAGE_SIZE));
        int pg = Math.max(0, page);
        if (from != null && to != null && from.isAfter(to)) {
            throw new UnprocessableException("Начало периода позже его окончания");
        }
        Set<String> scope = currentUser.isTenantScoped() ? tenantScope.rmas() : null;
        if (scope != null && (scope.isEmpty() || scope.contains("__none__"))) {
            return new PageResult(List.of(), pg, sz, 0, 0);
        }
        String term = emptyToNull(q);
        Specification<Malumotnoma> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (scope != null) {
                ps.add(root.get("organizationRma").in(scope));
            }
            if (from != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), startOf(from)));
            }
            if (to != null) {
                ps.add(cb.lessThan(root.get("createdAt"), startOf(to.plusDays(1))));
            }
            if (term != null) {
                Predicate byFio = cb.like(cb.lower(root.get("fio")), "%" + term.toLowerCase() + "%");
                String digits = term.startsWith("№") ? term.substring(1).trim() : term;
                ps.add(digits.matches("\\d{1,18}")
                        ? cb.or(cb.equal(root.get("number"), Long.parseLong(digits)), byFio)
                        : byFio);
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Page<Malumotnoma> p = malumotnomas.findAll(spec, PageRequest.of(pg, sz, Sort.by(Sort.Direction.DESC, "number")));
        return new PageResult(p.getContent(), pg, sz, p.getTotalElements(), p.getTotalPages());
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
        Malumotnoma m = new Malumotnoma();
        m.setFio(req.fio().trim());
        m.setOrganizationRma(currentUser.organizationRma().orElse(null));
        m.setIssuerRma(currentUser.rma().orElse(null));
        // «Выдал» на справке и «Кассир» в отчёте — Ф.И.О. кассира, а не логин (было «accountant»).
        m.setIssuerName(actorName());
        applyContent(m, req.transportTypeId(), req.age(), req.lines(), Set.of());
        m.setNumber(malumotnomas.nextNumber());
        return malumotnomas.save(m);
    }

    /** Правка справки (вид транспорта, льгота, маршруты); цена пересчитывается, фиксируется «Изменил». */
    @Transactional
    public Malumotnoma update(UUID id, UpdateRequest req) {
        Malumotnoma m = get(id);
        if (m.isLegacy()) {
            throw new UnprocessableException("Справка перенесена из архива «Роҳхат» — правке не подлежит");
        }
        if (m.isAnnulled()) {
            throw new UnprocessableException("Справка аннулирована — правке не подлежит");
        }
        Set<UUID> kept = new HashSet<>();
        m.getLines().forEach(l -> kept.add(l.getRoute().getId()));
        applyContent(m, req.transportTypeId(), req.age(), req.lines(), kept);
        m.setUpdaterName(actorName());
        return malumotnomas.save(m);
    }

    /**
     * Аннулирование вместо удаления: в «Роҳхат» удалить справку нельзя. Номер остаётся в журнале,
     * справка не входит в отчёт кассира, проверка по QR показывает «аннулирована».
     */
    @Transactional
    public Malumotnoma annul(UUID id, AnnulRequest req) {
        if (!(currentUser.hasRole("SYSTEM_ADMIN") || currentUser.hasRole("COMPANY_ADMIN")
                || currentUser.hasRole("BRANCH_ADMIN"))) {
            throw new ForbiddenException("Аннулировать справку может только администратор");
        }
        Malumotnoma m = get(id);
        if (m.isAnnulled()) {
            throw new UnprocessableException("Справка уже аннулирована");
        }
        String reason = req == null ? null : emptyToNull(req.reason());
        if (reason == null) {
            throw new UnprocessableException("Укажите причину аннулирования");
        }
        m.setAnnulledAt(OffsetDateTime.now());
        m.setAnnulledBy(actorName());
        m.setAnnulReason(reason.length() > 500 ? reason.substring(0, 500) : reason);
        return malumotnomas.save(m);
    }

    /** Вид, льгота, строки маршрутов и цена (снимок цены строки — на момент выдачи/правки). */
    private void applyContent(Malumotnoma m, Short transportTypeId, Short ageIn, List<LineRequest> lineReqs,
                              Set<UUID> keptRoutes) {
        short type = transportTypeId == null ? 0 : transportTypeId;
        if (!TYPE_NAME.containsKey(type)) {
            throw new UnprocessableException("Вид транспорта справки: 1 (автобус), 3 (микроавтобус) или 4 (легковой)");
        }
        List<LineRequest> reqs = lineReqs == null ? List.of()
                : lineReqs.stream().filter(l -> l != null && l.routeId() != null).toList();
        if (reqs.isEmpty()) {
            throw new UnprocessableException("Добавьте хотя бы один маршрут");
        }
        short age = ageIn != null && ageIn == 1 ? (short) 1 : (short) 0;
        m.setTransportTypeId(type);
        m.setAge(age);
        m.getLines().clear();

        BigDecimal sum = BigDecimal.ZERO;
        List<String> summary = new ArrayList<>();
        for (LineRequest lr : reqs) {
            MalumotnomaRoute route = routes.findById(lr.routeId())
                    .orElseThrow(() -> new UnprocessableException("Маршрут справки не найден"));
            if (!route.isActive() && !keptRoutes.contains(route.getId())) {
                throw new UnprocessableException("Маршрут «" + route.getName() + "» отключён");
            }
            BigDecimal linePrice = route.priceFor(type);
            if (lr.roundTrip()) {
                linePrice = linePrice.multiply(BigDecimal.valueOf(2));
            }
            MalumotnomaLine line = new MalumotnomaLine();
            line.setRoute(route);
            line.setRoundTrip(lr.roundTrip());
            line.setPrice(linePrice.setScale(2, RoundingMode.HALF_UP));
            m.addLine(line);
            sum = sum.add(linePrice);
            summary.add(route.getName().trim() + (lr.roundTrip() ? " (сафари рафту баргашт)" : ""));
        }
        if (age == 1) {
            sum = sum.multiply(BigDecimal.valueOf(0.5));
        }
        m.setPrice(sum.setScale(2, RoundingMode.HALF_UP));
        String routeSummary = String.join(", ", summary);
        m.setRouteSummary(routeSummary.length() > 1000 ? routeSummary.substring(0, 997) + "..." : routeSummary);
    }

    // ------------------------------------------------------------- отчёт

    public record ReportRow(UUID id, Long number, String fio, String transportType, boolean privileged,
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

        // Границы суток — по времени Душанбе (было UTC: справки 00:00–05:00 попадали в прошлый день).
        OffsetDateTime fromTs = startOf(from);
        OffsetDateTime toTs = startOf(to.plusDays(1));
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
                    m.getId(), m.getNumber(), m.getFio(), TYPE_NAME.getOrDefault(m.getTransportTypeId(), "—"),
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
        for (BigDecimal p : new BigDecimal[]{req.carPrice(), req.mbusPrice(), req.busPrice()}) {
            if (p != null && p.signum() < 0) {
                throw new UnprocessableException("Тариф маршрута не может быть отрицательным");
            }
        }
        r.setName(req.name().trim());
        r.setDistanceKm(req.distanceKm() == null ? 0 : req.distanceKm());
        r.setCarPrice(nz(req.carPrice()));
        r.setMbusPrice(nz(req.mbusPrice()));
        r.setBusPrice(nz(req.busPrice()));
        r.setActive(req.active() == null || req.active());
        return routes.save(r);
    }

    /** Удалить можно только неиспользованный маршрут; использованный — отключить (active = false). */
    @Transactional
    public void deleteRoute(UUID id) {
        requirePlatformAdmin();
        if (!routes.existsById(id)) {
            throw new NotFoundException("Маршрут не найден");
        }
        long used = malumotnomas.countLinesByRoute(id);
        if (used > 0) {
            throw new UnprocessableException("Маршрут использован в справках (" + used
                    + ") — удалить нельзя, отключите его");
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

    private String actorName() {
        return currentUser.fullName().filter(s -> !s.isBlank()).or(currentUser::username).orElse(null);
    }

    private static OffsetDateTime startOf(LocalDate d) {
        return d.atStartOfDay(PrintZone.ZONE).toOffsetDateTime();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String emptyToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
