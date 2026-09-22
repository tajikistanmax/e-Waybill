package tj.mintrans.epd.waybill.service;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.config.CurrentUser;
import tj.mintrans.epd.waybill.config.TenantScope;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.repository.WaybillRepository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Реестр ПЛ с серверной пагинацией (MIGRATION.md 8.4 — legacy DataTables server-side у всех CRUD-реестров
 * Waybill1ad/1a/3c/2b/5bbm/cmr/cargowaybill).
 *
 * <p>Все фильтры выполняются в SQL через {@link Specification}, включая поля JSON-снимков и {@code typeData}
 * ({@code jsonb_extract_path_text}); страница + общее число строк — {@code findAll(spec, pageable)}. Область
 * видимости та же, что у листинга: тенант — своя организация с филиалами (из токена), водитель — только свои
 * ПЛ, платформенные роли — все организации с необязательным явным {@code organizationRma}. Архив
 * (source = MIGRATED, ~2.3 млн историч. ПЛ) — только при {@code archived=true}.</p>
 */
@Service
public class WaybillRegistryService {

    static final String MIGRATED = "MIGRATED";
    static final int MAX_PAGE_SIZE = 1000;

    /** Параметры отбора (все необязательны). {@code from}/{@code to} — по дате начала действия (иначе создания), включительно. */
    public record Filter(String organizationRma, WaybillStatus status, WaybillType type, String vehicle, String driver,
                         String svc, String docKind, String client, LocalDate from, LocalDate to, String q,
                         boolean archived) {
    }

    public record PageResult(List<Waybill> content, int page, int size, long totalElements, int totalPages) {
    }

    /** Область видимости вызывающего: {@code rmas == null} — все организации; {@code none} — ничего не видно. */
    record Scope(Set<String> rmas, String driverRma, boolean none) {
    }

    private final WaybillRepository waybills;
    private final TenantScope tenantScope;
    private final CurrentUser currentUser;

    public WaybillRegistryService(WaybillRepository waybills, TenantScope tenantScope, CurrentUser currentUser) {
        this.waybills = waybills;
        this.tenantScope = tenantScope;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public PageResult page(Filter f, int page, int size) {
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Scope scope = scope(f.organizationRma());
        if (scope.none()) {
            return new PageResult(List.of(), p, s, 0, 0);
        }
        Page<Waybill> pg = waybills.findAll(spec(f, scope),
                PageRequest.of(p, s, Sort.by(Sort.Direction.DESC, "createdAt")));
        return new PageResult(pg.getContent(), p, s, pg.getTotalElements(), pg.getTotalPages());
    }

    /** Число ПЛ по статусам в области вызывающего без архива (карточки-счётчики над реестром). */
    @Transactional(readOnly = true)
    public Map<String, Long> statusCounts(String organizationRma) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (WaybillStatus st : WaybillStatus.values()) {
            out.put(st.name(), 0L);
        }
        Scope scope = scope(organizationRma);
        if (scope.none()) {
            return out;
        }
        if (scope.driverRma() != null) {
            // Водитель: только свои ПЛ — считаем по спецификации (небольшой объём).
            for (WaybillStatus st : WaybillStatus.values()) {
                Filter f = new Filter(null, st, null, null, null, null, null, null, null, null, null, false);
                out.put(st.name(), waybills.count(spec(f, scope)));
            }
            return out;
        }
        List<Object[]> rows = scope.rmas() == null
                ? waybills.countByStatusExcludingSource(MIGRATED)
                : waybills.countByStatusForOrganizationsExcludingSource(scope.rmas(), MIGRATED);
        for (Object[] r : rows) {
            out.put(((WaybillStatus) r[0]).name(), ((Number) r[1]).longValue());
        }
        return out;
    }

    private Scope scope(String requestedOrg) {
        if (tenantScope.isBounded()) {
            var scoped = tenantScope.rmas();
            if (scoped.isEmpty() || scoped.contains("__none__")) {
                return new Scope(Set.of(), null, true);
            }
            String driverRma = currentUser.hasRole("DRIVER") ? currentUser.rma().orElse("") : null;
            return new Scope(Set.copyOf(scoped), driverRma, false);
        }
        String org = norm(requestedOrg);
        return new Scope(org == null ? null : Set.of(org), null, false);
    }

    // ------------------------------------------------------------------ спецификация

    static Specification<Waybill> spec(Filter f, Scope scope) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (scope.rmas() != null) {
                ps.add(root.get("organizationRma").in(scope.rmas()));
            }
            if (scope.driverRma() != null) {
                ps.add(cb.or(cb.equal(root.get("driverRma"), scope.driverRma()),
                        cb.equal(root.get("secondDriverRma"), scope.driverRma())));
            }
            if (!f.archived()) {
                ps.add(cb.notEqual(root.get("source"), MIGRATED));
            }
            if (f.status() != null) {
                ps.add(cb.equal(root.get("status"), f.status()));
            }
            if (f.type() != null) {
                ps.add(cb.equal(root.get("waybillType"), f.type()));
            }
            String vehicle = norm(f.vehicle());
            if (vehicle != null) {
                ps.add(cb.equal(cb.upper(root.get("vehicleRegNumber")), vehicle.toUpperCase()));
            }
            String driver = norm(f.driver());
            if (driver != null) {
                if (isRma(driver)) {
                    ps.add(cb.or(cb.equal(root.get("driverRma"), driver), cb.equal(root.get("secondDriverRma"), driver)));
                } else {
                    ps.add(cb.like(cb.lower(json(cb, root.get("driverSnapshot"), "fullName")), contains(driver)));
                }
            }
            String[] svc = svcCodes(f.svc());
            if (svc != null) {
                ps.add(cb.or(cb.equal(json(cb, root.get("typeData"), "serviceKind"), svc[0]),
                        cb.equal(json(cb, root.get("typeData"), "typeService"), svc[1])));
            }
            Set<WaybillType> docTypes = docKindTypes(f.docKind());
            if (docTypes != null) {
                Expression<String> sender = json(cb, root.get("typeData"), "senderName");
                ps.add(root.get("waybillType").in(docTypes));
                ps.add(cb.isNotNull(sender));
                ps.add(cb.notEqual(sender, ""));
            }
            String client = norm(f.client());
            if (client != null) {
                String pattern = contains(client);
                ps.add(cb.or(
                        cb.like(cb.lower(json(cb, root.get("typeData"), "senderName")), pattern),
                        cb.like(cb.lower(json(cb, root.get("typeData"), "receiverName")), pattern),
                        cb.like(cb.lower(json(cb, root.get("typeData"), "forwarderName")), pattern),
                        cb.like(cb.lower(json(cb, root.get("typeData"), "clientName")), pattern)));
            }
            if (f.from() != null || f.to() != null) {
                Expression<OffsetDateTime> day = cb.coalesce(root.<OffsetDateTime>get("validFrom"), root.<OffsetDateTime>get("createdAt"));
                if (f.from() != null) {
                    ps.add(cb.greaterThanOrEqualTo(day, WaybillPeriodScan.lower(f.from())));
                }
                if (f.to() != null) {
                    ps.add(cb.lessThan(day, WaybillPeriodScan.upper(f.to())));
                }
            }
            String q = norm(f.q());
            if (q != null) {
                String pattern = contains(q);
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("number")), pattern),
                        cb.like(cb.lower(root.get("vehicleRegNumber")), pattern),
                        cb.like(root.get("driverRma"), "%" + q + "%"),
                        cb.like(root.get("organizationRma"), "%" + q + "%"),
                        cb.like(cb.lower(json(cb, root.get("driverSnapshot"), "fullName")), pattern),
                        cb.like(cb.lower(json(cb, root.get("organizationSnapshot"), "name")), pattern)));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
    }

    /** Текстовое значение ключа JSON-колонки (PostgreSQL {@code jsonb_extract_path_text}). */
    private static Expression<String> json(CriteriaBuilder cb, Path<?> column, String key) {
        return cb.function("jsonb_extract_path_text", String.class, column, cb.literal(key));
    }

    private static String contains(String s) {
        return "%" + s.toLowerCase() + "%";
    }

    // ------------------------------------------------------------------ чистые помощники (тестируются)

    /** Виды ПЛ, у которых бывает документ {@code docKind}: борхат (2-Б/ОГ) или СМР (5Б-БМ); иначе null. */
    static Set<WaybillType> docKindTypes(String docKind) {
        String k = norm(docKind);
        if (k == null) {
            return null;
        }
        return switch (k.toLowerCase()) {
            case "attachment" -> Set.of(WaybillType.WB_TRUCK, WaybillType.WB_DANGEROUS);
            case "cmr" -> Set.of(WaybillType.WB_TRUCK_INTL);
            default -> null;
        };
    }

    /**
     * Вид обслуживания 3-С: живые ПЛ несут {@code typeData.serviceKind} (TAXI/ROUTE/HOURLY), мигрированные —
     * числовой {@code typeService} (1 такси, 2 маршрут, 3 почасовой). Возвращает пару кодов или null.
     */
    static String[] svcCodes(String svc) {
        String s = norm(svc);
        if (s == null) {
            return null;
        }
        return switch (s.toUpperCase()) {
            case "TAXI", "1" -> new String[]{"TAXI", "1"};
            case "ROUTE", "2" -> new String[]{"ROUTE", "2"};
            case "HOURLY", "3" -> new String[]{"HOURLY", "3"};
            default -> null;
        };
    }

    static boolean isRma(String s) {
        return s != null && s.matches("\\d{9,10}");
    }

    static String norm(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Для тестов: предикаты-помощники без CriteriaBuilder — какие условия активны у фильтра. */
    static List<String> activeConditions(Filter f) {
        List<String> out = new ArrayList<>();
        if (!f.archived()) out.add("source<>MIGRATED");
        if (f.status() != null) out.add("status");
        if (f.type() != null) out.add("type");
        if (norm(f.vehicle()) != null) out.add("vehicle");
        if (norm(f.driver()) != null) out.add(isRma(norm(f.driver())) ? "driver:rma" : "driver:name");
        if (svcCodes(f.svc()) != null) out.add("svc");
        if (docKindTypes(f.docKind()) != null) out.add("docKind");
        if (norm(f.client()) != null) out.add("client");
        if (f.from() != null) out.add("from");
        if (f.to() != null) out.add("to");
        if (norm(f.q()) != null) out.add("q");
        return out;
    }
}
