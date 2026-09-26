package tj.mintrans.epd.waybill.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.config.TenantScope;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.domain.WaybillType;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Показатели главной панели (сверка 25.09, H4): раньше панель считала их в браузере по ответу
 * {@code GET /waybills}, ограниченному 1000 последними листами, — у организации с большим парком
 * «Всего», «Завершено», разбивка по видам и динамика были занижены. Теперь — агрегаты БД по всей
 * области видимости (тенант — свои организации, платформенные роли — все). Как и прежний список,
 * считаются листы, выписанные в платформе (без перенесённого архива {@code source = MIGRATED}).
 */
@Service
public class DashboardService {

    public record Day(LocalDate date, long created, long completed, long active, long cancelled) {
    }

    public record TypeCount(String type, long count) {
    }

    public record Stats(long total, long today, long onLine, long completed, long cancelled,
                        List<Day> days, List<TypeCount> byType, List<Waybill> recent) {
    }

    static final Set<WaybillStatus> ON_LINE = EnumSet.of(WaybillStatus.ISSUED, WaybillStatus.ACTIVE, WaybillStatus.RETURNED);
    static final Set<WaybillStatus> CANCEL_LIKE = EnumSet.of(WaybillStatus.CANCELLED, WaybillStatus.EXPIRED, WaybillStatus.BLOCKED);
    private static final String MIGRATED = "MIGRATED";

    private final EntityManager em;
    private final TenantScope tenantScope;

    public DashboardService(EntityManager em, TenantScope tenantScope) {
        this.em = em;
        this.tenantScope = tenantScope;
    }

    @Transactional(readOnly = true)
    public Stats stats() {
        boolean bounded = tenantScope.isBounded();
        Set<String> rmas = bounded ? tenantScope.rmas() : Set.of();
        if (bounded && (rmas.isEmpty() || rmas.contains("__none__"))) {
            return new Stats(0, 0, 0, 0, 0, emptyDays(), List.of(), List.of());
        }
        String scope = " where w.source <> :migrated" + (bounded ? " and w.organizationRma in :rmas" : "");

        Map<WaybillStatus, Long> byStatus = new LinkedHashMap<>();
        for (Object[] r : query("select w.status, count(w) from Waybill w" + scope + " group by w.status", Object[].class, rmas, bounded)
                .getResultList()) {
            byStatus.put((WaybillStatus) r[0], ((Number) r[1]).longValue());
        }
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long onLine = sum(byStatus, ON_LINE);
        long completed = sum(byStatus, EnumSet.of(WaybillStatus.COMPLETED));
        long cancelled = sum(byStatus, CANCEL_LIKE);

        List<TypeCount> byType = new ArrayList<>();
        for (Object[] r : query("select w.waybillType, count(w) from Waybill w" + scope + " group by w.waybillType"
                + " order by count(w) desc", Object[].class, rmas, bounded).getResultList()) {
            byType.add(new TypeCount(((WaybillType) r[0]).name(), ((Number) r[1]).longValue()));
        }

        // Последние 7 суток — по дате создания в часовом поясе службы (TZ Asia/Dushanbe на стенде).
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        OffsetDateTime since = today.minusDays(6).atStartOfDay(zone).toOffsetDateTime();
        Map<LocalDate, long[]> perDay = new LinkedHashMap<>();
        for (int i = 6; i >= 0; i--) {
            perDay.put(today.minusDays(i), new long[4]);
        }
        var recentQ = query("select w.createdAt, w.status, count(w) from Waybill w" + scope
                + " and w.createdAt >= :since group by w.createdAt, w.status", Object[].class, rmas, bounded);
        recentQ.setParameter("since", since);
        for (Object[] r : recentQ.getResultList()) {
            LocalDate d = ((OffsetDateTime) r[0]).atZoneSameInstant(zone).toLocalDate();
            long[] c = perDay.get(d);
            if (c == null) {
                continue;
            }
            WaybillStatus st = (WaybillStatus) r[1];
            long n = ((Number) r[2]).longValue();
            c[0] += n;
            if (st == WaybillStatus.COMPLETED) c[1] += n;
            if (st == WaybillStatus.ACTIVE || st == WaybillStatus.RETURNED) c[2] += n;
            if (st == WaybillStatus.CANCELLED || st == WaybillStatus.EXPIRED) c[3] += n;
        }
        List<Day> days = new ArrayList<>();
        perDay.forEach((d, c) -> days.add(new Day(d, c[0], c[1], c[2], c[3])));
        long todayCount = days.getLast().created();

        List<Waybill> recent = query("select w from Waybill w" + scope + " order by w.createdAt desc", Waybill.class, rmas, bounded)
                .setMaxResults(6).getResultList();
        return new Stats(total, todayCount, onLine, completed, cancelled, days, byType, recent);
    }

    private <T> TypedQuery<T> query(String jpql, Class<T> type, Set<String> rmas, boolean bounded) {
        TypedQuery<T> q = em.createQuery(jpql, type);
        q.setParameter("migrated", MIGRATED);
        if (bounded) {
            q.setParameter("rmas", rmas);
        }
        return q;
    }

    private static long sum(Map<WaybillStatus, Long> m, Set<WaybillStatus> keys) {
        return keys.stream().mapToLong(k -> m.getOrDefault(k, 0L)).sum();
    }

    private static List<Day> emptyDays() {
        LocalDate today = LocalDate.now();
        List<Day> r = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            r.add(new Day(today.minusDays(i), 0, 0, 0, 0));
        }
        return r;
    }
}
