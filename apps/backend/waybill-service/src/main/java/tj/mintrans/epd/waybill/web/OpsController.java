package tj.mintrans.epd.waybill.web;

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
 * Эксплуатационная сводка waybill-service для админ-панели («Настройки → Интеграции/
 * Производительность/Нумерация»): реальные флаги интеграций и счётчики нумерации
 * вместо декоративных карточек. Только SYSTEM_ADMIN; секреты НЕ отдаются.
 */
@RestController
@RequestMapping("/api/v1/ops")
public class OpsController {

    private final JdbcTemplate jdbc;
    private final boolean paymentEnabled;
    private final boolean aggregatorOpen;
    private final String signingMode;
    private final String kafkaBootstrap;
    private final String eventsTopic;

    public OpsController(JdbcTemplate jdbc,
                         @Value("${epd.payment.enabled}") boolean paymentEnabled,
                         @Value("${epd.security.aggregator-open}") boolean aggregatorOpen,
                         @Value("${epd.signing.mode}") String signingMode,
                         @Value("${spring.kafka.bootstrap-servers}") String kafkaBootstrap,
                         @Value("${epd.events.topic}") String eventsTopic) {
        this.jdbc = jdbc;
        this.paymentEnabled = paymentEnabled;
        this.aggregatorOpen = aggregatorOpen;
        this.signingMode = signingMode;
        this.kafkaBootstrap = kafkaBootstrap;
        this.eventsTopic = eventsTopic;
    }

    @GetMapping("/overview")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public Map<String, Object> overview() {
        var out = new LinkedHashMap<String, Object>();
        out.put("paymentEnabled", paymentEnabled);
        out.put("aggregatorOpen", aggregatorOpen);
        out.put("signingMode", signingMode);
        out.put("kafkaBootstrap", kafkaBootstrap);
        out.put("eventsTopic", eventsTopic);
        out.put("numbering", numbering());
        out.put("runtime", runtime());
        return out;
    }

    /** Счётчики нумерации по типам: всего ПЛ, с номером, последний присвоенный номер. */
    private List<Map<String, Object>> numbering() {
        try {
            return jdbc.queryForList("""
                    select waybill_type as type,
                           count(*) as total,
                           count(number) as numbered,
                           max(number) as last_number
                    from waybill group by waybill_type order by waybill_type""");
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
