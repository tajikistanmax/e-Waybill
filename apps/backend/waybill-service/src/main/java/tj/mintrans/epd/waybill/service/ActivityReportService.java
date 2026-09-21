package tj.mintrans.epd.waybill.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.config.TenantScope;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Активность ТС / водителей за период — число ПЛ выбранных видов по госномеру или РМА водителя
 * (MIGRATION.md 8.5/8.6: legacy фильтры реестров {@code active_trans / inactive_trans / active2b /
 * 4-роҳхат(3с) / 2-роҳхат(2b)} и {@code active_drivers / inactive_drivers}). Агрегат в БД по
 * {@code created_at} периода, все статусы (как legacy COUNT). Клиент (реестр парка) сам делит на
 * «активные / без ПЛ / ровно N».
 *
 * <p>Мультиарендность — как у остальных отчётов: тенант видит свою область, платформенные роли —
 * запрошенную организацию или все.</p>
 */
@Service
public class ActivityReportService {

    /** По какому ключу считать. */
    public enum By { VEHICLE, DRIVER }

    /** Ключ (госномер / РМА водителя) и число ПЛ за период. */
    public record Row(String key, long waybills) {
    }

    private final WaybillRepository waybills;
    private final TenantScope tenantScope;

    public ActivityReportService(WaybillRepository waybills, TenantScope tenantScope) {
        this.waybills = waybills;
        this.tenantScope = tenantScope;
    }

    /**
     * @param types виды ПЛ; пусто/{@code null} — все виды
     * @param requestedOrg организация для платформенных ролей; {@code null} — все (у тенанта игнорируется)
     */
    @Transactional(readOnly = true)
    public List<Row> activity(By by, LocalDate from, LocalDate to, Collection<WaybillType> types, String requestedOrg) {
        if (by == null || from == null || to == null) {
            throw new UnprocessableException("Укажите by, from и to");
        }
        if (to.isBefore(from)) {
            throw new UnprocessableException("Период задан неверно: from > to");
        }
        Set<WaybillType> typeSet = types == null || types.isEmpty()
                ? EnumSet.allOf(WaybillType.class) : EnumSet.copyOf(types);
        Set<String> scope = resolveScope(requestedOrg);
        var lower = WaybillPeriodScan.lower(from);
        var upper = WaybillPeriodScan.upper(to);

        List<Object[]> rows;
        if (scope == null) {
            rows = by == By.VEHICLE ? waybills.countByVehicle(typeSet, lower, upper)
                    : waybills.countByDriver(typeSet, lower, upper);
        } else if (scope.isEmpty()) {
            rows = List.of();
        } else {
            rows = by == By.VEHICLE ? waybills.countByVehicleForOrganizations(scope, typeSet, lower, upper)
                    : waybills.countByDriverForOrganizations(scope, typeSet, lower, upper);
        }
        List<Row> result = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            if (r[0] == null) {
                continue;
            }
            result.add(new Row(r[0].toString(), ((Number) r[1]).longValue()));
        }
        result.sort((a, b) -> a.key().compareToIgnoreCase(b.key()));
        return result;
    }

    /** Как в {@link WaybillReportService}: тенант — своя область («__none__» → пусто), платформа — запрошенная или все. */
    private Set<String> resolveScope(String requested) {
        if (tenantScope.isBounded()) {
            Set<String> s = tenantScope.rmas();
            return s == null || s.contains("__none__") ? Set.of() : s;
        }
        return requested == null || requested.isBlank() ? null : Set.of(requested.trim());
    }
}
