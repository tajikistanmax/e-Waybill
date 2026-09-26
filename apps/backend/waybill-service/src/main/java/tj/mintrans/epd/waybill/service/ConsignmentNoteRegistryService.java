package tj.mintrans.epd.waybill.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.config.CurrentUser;
import tj.mintrans.epd.waybill.config.TenantScope;
import tj.mintrans.epd.waybill.domain.ConsignmentNote;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Реестр борхатов 2-Б (legacy {@code CargoWaybillAttachment1/2CrudController} + {@code CWControllerTrait}):
 * фильтры «отправитель / компания / экспедитор / дата борхата», поиск по номеру. Область видимости —
 * как legacy {@code RoleTrait::cargoWaybillHasRole}: платформенные роли — всё; перевозчик — свои
 * организации (в legacy роль company видела борхаты только в карточке листа); грузоотправитель и
 * экспедитор — борхаты, где они указаны отправителем / экспедитором (id клиента из токена).
 */
@Service
public class ConsignmentNoteRegistryService {

    public record Filter(LocalDate from, LocalDate to, String organizationRma, String sender, String forwarder,
                         String q, Integer kind) {
    }

    public record Row(UUID id, Long number, LocalDate noteDate, short kind,
                      String payerName, String senderName, String receiverName, String forwarderName,
                      String cargoName, BigDecimal cargoAmount, BigDecimal cargoWeight, BigDecimal distance,
                      Integer trips, double transportWork,
                      UUID waybillId, String waybillNumber, String waybillStatus,
                      String organizationRma, String organizationName, String vehicleRegNumber) {
    }

    public record Totals(long count, double transportWork, double trips, double weight) {
    }

    public record PageResult(List<Row> content, int page, int size, long totalElements, int totalPages,
                             Totals totals, String scope) {
    }

    private final EntityManager em;
    private final TenantScope tenantScope;
    private final CurrentUser currentUser;

    public ConsignmentNoteRegistryService(EntityManager em, TenantScope tenantScope, CurrentUser currentUser) {
        this.em = em;
        this.tenantScope = tenantScope;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public PageResult list(Filter f, int page, int size) {
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), 500);
        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<Tuple> q = cb.createTupleQuery();
        Root<ConsignmentNote> n = q.from(ConsignmentNote.class);
        Root<Waybill> w = q.from(Waybill.class);
        q.multiselect(n.alias("n"), w.get("number").alias("wn"), w.get("status").alias("ws"),
                w.get("organizationRma").alias("org"), w.get("organizationSnapshot").alias("orgSnap"),
                w.get("vehicleRegNumber").alias("plate"));
        q.where(predicates(cb, n, w, f).toArray(Predicate[]::new));
        q.orderBy(cb.desc(n.get("noteDate")), cb.desc(n.get("number")));
        List<Tuple> rows = em.createQuery(q).setFirstResult(p * s).setMaxResults(s).getResultList();

        CriteriaQuery<Tuple> t = cb.createTupleQuery();
        Root<ConsignmentNote> tn = t.from(ConsignmentNote.class);
        Root<Waybill> tw = t.from(Waybill.class);
        Expression<BigDecimal> weight = cb.coalesce(tn.<BigDecimal>get("cargoWeight"), BigDecimal.ZERO);
        Expression<BigDecimal> dist = cb.coalesce(tn.<BigDecimal>get("distance"), BigDecimal.ZERO);
        Expression<Integer> trips = cb.coalesce(tn.<Integer>get("trips"), 1);
        Predicate kind2 = cb.equal(tn.get("kind"), (short) 2);
        Expression<Number> pExpr = cb.<Number>selectCase()
                .when(kind2, cb.prod(weight, dist))
                .otherwise(cb.prod(cb.prod(weight, dist), trips));
        Expression<Number> zExpr = cb.<Number>selectCase().when(kind2, 1).otherwise(trips);
        Expression<Number> tExpr = cb.<Number>selectCase().when(kind2, weight).otherwise(cb.prod(weight, trips));
        t.multiselect(cb.count(tn).alias("c"), cb.sum(pExpr).alias("p"), cb.sum(zExpr).alias("z"), cb.sum(tExpr).alias("t"));
        t.where(predicates(cb, tn, tw, f).toArray(Predicate[]::new));
        Tuple agg = em.createQuery(t).getSingleResult();
        long total = agg.get("c", Long.class);
        Totals totals = new Totals(total, round(num(agg.get("p"))), num(agg.get("z")), round(num(agg.get("t"))));

        List<Row> content = new ArrayList<>();
        for (Tuple r : rows) {
            ConsignmentNote note = r.get("n", ConsignmentNote.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> snap = (Map<String, Object>) r.get("orgSnap");
            Object status = r.get("ws");
            content.add(new Row(note.getId(), note.getNumber(), note.getNoteDate(), note.getKind(),
                    note.getPayerName(), note.getSenderName(), note.getReceiverName(), note.getForwarderName(),
                    note.getCargoName(), note.getCargoAmount(), note.getCargoWeight(), note.getDistance(),
                    note.getTrips(), round(note.transportWork()),
                    note.getWaybillId(), (String) r.get("wn"), status == null ? null : status.toString(),
                    (String) r.get("org"), snap == null || snap.get("name") == null ? null : snap.get("name").toString(),
                    (String) r.get("plate")));
        }
        return new PageResult(content, p, s, total, (int) Math.ceil(total / (double) s), totals, scopeName());
    }

    public record NoteRef(ConsignmentNote note, Waybill waybill) {
    }

    /** Борхат из реестра — для печати: та же область видимости, что у списка. */
    @Transactional(readOnly = true)
    public NoteRef noteWithWaybill(UUID noteId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Tuple> q = cb.createTupleQuery();
        Root<ConsignmentNote> n = q.from(ConsignmentNote.class);
        Root<Waybill> w = q.from(Waybill.class);
        q.multiselect(n.alias("n"), w.alias("w"));
        List<Predicate> ps = predicates(cb, n, w, new Filter(null, null, null, null, null, null, null));
        ps.add(cb.equal(n.get("id"), noteId));
        q.where(ps.toArray(Predicate[]::new));
        List<Tuple> r = em.createQuery(q).getResultList();
        if (r.isEmpty()) {
            throw new NotFoundException("Борхат не найден");
        }
        return new NoteRef(r.getFirst().get("n", ConsignmentNote.class), r.getFirst().get("w", Waybill.class));
    }

    // ------------------------------------------------------------------

    /** Внешний кабинет (грузоотправитель / экспедитор): область — клиенты из токена, а не организации. */
    private boolean external() {
        return tenantScope.isBounded()
                && (currentUser.hasRole(ConsignmentAccess.ROLE_SENDER) || currentUser.hasRole(ConsignmentAccess.ROLE_FORWARDER));
    }

    private String scopeName() {
        if (!tenantScope.isBounded()) {
            return "all";
        }
        return external() ? "clients" : "organizations";
    }

    private List<Predicate> predicates(CriteriaBuilder cb, Root<ConsignmentNote> n, Root<Waybill> w, Filter f) {
        List<Predicate> ps = new ArrayList<>();
        ps.add(cb.equal(n.get("waybillId"), w.get("id")));
        // Борхаты аннулированного листа не учитываются — как аннулированные листы в отчётах (legacy soft delete).
        ps.add(cb.notEqual(w.get("status"), tj.mintrans.epd.waybill.domain.WaybillStatus.CANCELLED));
        if (tenantScope.isBounded()) {
            if (external()) {
                Set<UUID> clients = clientUuids();
                List<Predicate> any = new ArrayList<>();
                if (currentUser.hasRole(ConsignmentAccess.ROLE_SENDER) && !clients.isEmpty()) {
                    any.add(n.get("senderId").in(clients));
                }
                if (currentUser.hasRole(ConsignmentAccess.ROLE_FORWARDER) && !clients.isEmpty()) {
                    any.add(n.get("forwarderId").in(clients));
                }
                ps.add(any.isEmpty() ? cb.disjunction() : cb.or(any.toArray(Predicate[]::new)));
            } else {
                ps.add(w.get("organizationRma").in(tenantScope.rmas()));
            }
        }
        if (f.from() != null) {
            ps.add(cb.greaterThanOrEqualTo(n.get("noteDate"), f.from()));
        }
        if (f.to() != null) {
            ps.add(cb.lessThanOrEqualTo(n.get("noteDate"), f.to()));
        }
        if (notBlank(f.organizationRma())) {
            ps.add(cb.equal(w.get("organizationRma"), f.organizationRma().trim()));
        }
        if (notBlank(f.sender())) {
            ps.add(cb.like(cb.lower(n.get("senderName")), like(f.sender())));
        }
        if (notBlank(f.forwarder())) {
            ps.add(cb.like(cb.lower(n.get("forwarderName")), like(f.forwarder())));
        }
        if (f.kind() != null && (f.kind() == 1 || f.kind() == 2)) {
            ps.add(cb.equal(n.get("kind"), f.kind().shortValue()));
        }
        if (notBlank(f.q())) {
            String pat = like(f.q());
            ps.add(cb.or(
                    // .as(String) в Hibernate 6 — лишь подсказка типа (bigint ~~ text в SQL); нужен настоящий CAST.
                    cb.like(((org.hibernate.query.criteria.JpaExpression<?>) n.<Long>get("number")).cast(String.class), pat),
                    cb.like(cb.lower(w.get("number")), pat),
                    cb.like(cb.lower(w.get("vehicleRegNumber")), pat),
                    cb.like(cb.lower(n.get("payerName")), pat),
                    cb.like(cb.lower(n.get("receiverName")), pat),
                    cb.like(cb.lower(n.get("cargoName")), pat)));
        }
        return ps;
    }

    private Set<UUID> clientUuids() {
        Set<UUID> r = new java.util.HashSet<>();
        for (String c : currentUser.clientIds()) {
            try {
                r.add(UUID.fromString(c));
            } catch (IllegalArgumentException ignored) {
                // не UUID — не клиент справочника
            }
        }
        return r;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String like(String s) {
        return "%" + s.trim().toLowerCase() + "%";
    }

    private static double num(Object o) {
        return o instanceof Number x ? x.doubleValue() : 0d;
    }

    private static double round(double v) {
        return Math.round(v * 1000d) / 1000d;
    }
}
