package tj.mintrans.epd.masterdata.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Эксплуатационная сводка master-data для админ-панели («Настройки → Интеграции/
 * Производительность/Резервные копии»): реальное состояние вместо декоративных карточек.
 * Только SYSTEM_ADMIN; секреты (пароли/URL с кредами) НЕ отдаются.
 */
@RestController
@RequestMapping("/api/v1/ops")
public class OpsController {

    private final JdbcTemplate jdbc;
    private final String unifiedPlatformMode;

    public OpsController(JdbcTemplate jdbc,
                         @Value("${epd.unified-platform.mode}") String unifiedPlatformMode) {
        this.jdbc = jdbc;
        this.unifiedPlatformMode = unifiedPlatformMode;
    }

    @GetMapping("/overview")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public Map<String, Object> overview() {
        var out = new LinkedHashMap<String, Object>();
        out.put("unifiedPlatformMode", unifiedPlatformMode);
        out.put("databases", databaseSizes());
        out.put("runtime", runtime());
        return out;
    }

    /** Размеры всех БД кластера (masterdata, waybill, epd) — для страницы резервных копий. */
    private List<Map<String, Object>> databaseSizes() {
        try {
            return jdbc.queryForList("""
                    select datname as name, pg_database_size(datname) as size_bytes
                    from pg_database where datistemplate = false order by datname""");
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private Map<String, Object> runtime() {
        var rt = Runtime.getRuntime();
        return Map.of(
                "uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime(),
                "heapUsedBytes", rt.totalMemory() - rt.freeMemory(),
                "heapMaxBytes", rt.maxMemory(),
                "processors", rt.availableProcessors());
    }
}
