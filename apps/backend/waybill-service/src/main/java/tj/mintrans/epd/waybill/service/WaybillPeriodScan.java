package tj.mintrans.epd.waybill.service;

import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.calc.CalcLogSummary;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Проход по путевым листам ПЕРИОДА для отчётов — потоком, с ограниченной памятью.
 *
 * <p>До 21.09 все отчётные сервисы делали {@code waybills.findAll()} и фильтровали период в памяти.
 * После миграции Ф5 (≈2,3 млн архивных ПЛ legacy) это гарантированно роняло waybill-service в
 * {@code OutOfMemoryError} на любом отчёте платформенной роли (в т.ч. на главной панели).</p>
 *
 * <ul>
 *   <li>период — по {@code created_at}, как и раньше: день {@code from} 00:00 включительно …
 *       день {@code to} 24:00 исключительно в зоне сервера (совпадает с прежним
 *       {@code createdAt.toLocalDate()});</li>
 *   <li>выборка — JPA-{@code Stream} с fetch size 500; persistence-context очищается каждые
 *       {@value #CLEAR_EVERY} строк, чтобы сущности (и то, что подгрузил расчёт по каждому ПЛ)
 *       не копились;</li>
 *   <li>{@link #forEach} — для «тяжёлых» отчётов (расчёт/запросы по каждому ПЛ): предохранитель —
 *       если в периоде больше {@code epd.reports.max-rows} ПЛ (по умолчанию {@value #DEFAULT_MAX_ROWS}),
 *       запрос отклоняется с 422 и понятным текстом (сузить период/организацию) — честный отказ
 *       вместо падения сервиса; {@link #forEachAll} — для «лёгких» счётных отчётов без лимита
 *       (память всё равно ограничена, время — пропорционально числу строк);</li>
 *   <li>{@link #forEachCompleted} — только завершённые ПЛ, фильтр статуса в SQL (сводный перевозок,
 *       тренд): архив (ARCHIVED) через приложение не тянется.</li>
 * </ul>
 */
@Component
public class WaybillPeriodScan {

    static final int CLEAR_EVERY = 500;
    static final int DEFAULT_MAX_ROWS = 100_000;

    private final WaybillRepository waybills;
    private final EntityManager entityManager;
    private final int maxRows;

    public WaybillPeriodScan(WaybillRepository waybills, EntityManager entityManager,
                             @Value("${epd.reports.max-rows:" + DEFAULT_MAX_ROWS + "}") int maxRows) {
        this.waybills = waybills;
        this.entityManager = entityManager;
        this.maxRows = maxRows > 0 ? maxRows : DEFAULT_MAX_ROWS;
    }

    /** Нижняя граница периода: начало дня {@code from} в зоне сервера. */
    static OffsetDateTime lower(LocalDate from) {
        return from.atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
    }

    /** Верхняя граница (исключительно): начало дня, следующего за {@code to}. */
    static OffsetDateTime upper(LocalDate to) {
        return to.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
    }

    /** Сколько ПЛ в периоде (для предохранителя и оценок). {@code scope == null} — все организации. */
    @Transactional(readOnly = true)
    public long count(LocalDate from, LocalDate to, Set<String> scope) {
        return count(from, to, scope, null);
    }

    private long count(LocalDate from, LocalDate to, Set<String> scope, java.util.Collection<WaybillStatus> statuses) {
        OffsetDateTime lo = lower(from);
        OffsetDateTime hi = upper(to);
        if (scope != null && scope.isEmpty()) {
            return 0;
        }
        if (statuses == null) {
            return scope == null
                    ? waybills.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(lo, hi)
                    : waybills.countByOrganizationRmaInAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(scope, lo, hi);
        }
        return scope == null
                ? waybills.countByStatusInAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(statuses, lo, hi)
                : waybills.countByOrganizationRmaInAndStatusInAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(scope, statuses, lo, hi);
    }

    /**
     * Проход по ПЛ периода в порядке created_at — с предохранителем по объёму (для отчётов с расчётом
     * или запросами по каждому листу).
     *
     * @param scope    набор организаций (тенант) или {@code null} — все
     * @param consumer обработчик каждой строки; накапливать должен агрегаты, не сущности
     * @throws UnprocessableException если ПЛ в периоде больше лимита {@code epd.reports.max-rows}
     */
    @Transactional(readOnly = true)
    public void forEach(LocalDate from, LocalDate to, Set<String> scope, Consumer<Waybill> consumer) {
        run(from, to, scope, null, true, consumer);
    }

    /** Проход по ПЛ периода без предохранителя — для лёгких счётных отчётов (память ограничена, время — по объёму). */
    @Transactional(readOnly = true)
    public void forEachAll(LocalDate from, LocalDate to, Set<String> scope, Consumer<Waybill> consumer) {
        run(from, to, scope, null, false, consumer);
    }

    /** Только отработанные ПЛ периода — закрытые и архивные (фильтр статуса в SQL), с предохранителем. */
    @Transactional(readOnly = true)
    public void forEachCompleted(LocalDate from, LocalDate to, Set<String> scope, Consumer<Waybill> consumer) {
        run(from, to, scope, WaybillStatus.FINISHED, true, consumer);
    }

    private void run(LocalDate from, LocalDate to, Set<String> scope, java.util.Collection<WaybillStatus> statuses,
                     boolean capped, Consumer<Waybill> consumer) {
        if (from == null || to == null || to.isBefore(from)) {
            return;
        }
        if (scope != null && scope.isEmpty()) {
            return;
        }
        if (capped) {
            long total = count(from, to, scope, statuses);
            if (total > maxRows) {
                throw new UnprocessableException(
                        "Слишком большой объём для построчного отчёта: %d путевых листов в периоде %s — %s (лимит %d). "
                                .formatted(total, from, to, maxRows)
                                + "Сузьте период или выберите организацию.");
            }
        }
        OffsetDateTime lo = lower(from);
        OffsetDateTime hi = upper(to);
        // Предупреждения расчёта о незаполненных данных (тариф, доля дохода, год выпуска…) за проход
        // сводятся в одну строку лога вместо строки на каждый лист (CalcLogSummary).
        String what = from + " — " + to + (scope == null ? " (все организации)" : " (организаций: " + scope.size() + ")");
        try (CalcLogSummary.Scope summary = CalcLogSummary.open(what);
             Stream<Waybill> stream = open(scope, statuses, lo, hi)) {
            int[] seen = {0};
            stream.forEach(wb -> {
                consumer.accept(wb);
                summary.item();
                if (++seen[0] % CLEAR_EVERY == 0) {
                    entityManager.clear();
                }
            });
        }
    }

    private Stream<Waybill> open(Set<String> scope, java.util.Collection<WaybillStatus> statuses, OffsetDateTime lo, OffsetDateTime hi) {
        if (statuses == null) {
            return scope == null
                    ? waybills.streamByPeriod(lo, hi)
                    : waybills.streamByPeriodAndOrganizations(scope, lo, hi);
        }
        return scope == null
                ? waybills.streamByPeriodAndStatuses(statuses, lo, hi)
                : waybills.streamByPeriodAndOrganizationsAndStatuses(scope, statuses, lo, hi);
    }
}
