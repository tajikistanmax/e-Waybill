package tj.mintrans.epd.waybill.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Мост доменных событий в Kafka: слушает {@link WaybillStatusChanged} ПОСЛЕ commit
 * (событие уходит только для реально сохранённых переходов) и публикует JSON
 * в topic epd.waybill.status с ключом waybillId (порядок в пределах документа).
 *
 * Отказ Kafka не ломает бизнес-операцию — событие логируется и теряется
 * (TODO prod: outbox-паттерн для гарантированной доставки).
 */
@Component
public class KafkaEventBridge {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventBridge.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final String topic;

    public KafkaEventBridge(KafkaTemplate<String, String> kafka,
                            ObjectMapper objectMapper,
                            @Value("${epd.events.topic:epd.waybill.status}") String topic) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @TransactionalEventListener
    public void on(WaybillStatusChanged event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            kafka.send(topic, event.waybillId().toString(), payload)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.warn("Kafka недоступна — событие {} по ПЛ {} не доставлено: {}",
                                    event.toStatus(), event.waybillId(), ex.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.warn("Не удалось опубликовать событие по ПЛ {}: {}", event.waybillId(), e.getMessage());
        }
    }
}
