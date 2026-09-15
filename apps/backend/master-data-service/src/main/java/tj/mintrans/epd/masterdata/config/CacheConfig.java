package tj.mintrans.epd.masterdata.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.cache.annotation.CachingConfigurer;

import java.time.Duration;

/**
 * Кэширование не-мутабельных, часто читаемых справочных данных master-data
 * (классификаторы по категории и эффективные политики) в Redis.
 *
 * <p>Зачем Redis, а не локальный кэш: сервис работает в нескольких репликах за
 * reverse-proxy — общий кэш даёт согласованность и корректную инвалидацию сразу
 * во всех инстансах (запись в одной реплике очищает общий кэш для всех).</p>
 *
 * <p>Устойчивость к недоступности Redis: Lettuce-подключение ленивое (на старте к
 * Redis не ходим — приложение поднимается даже без Redis), а {@link #errorHandler()}
 * гасит ошибки кэша с логом. Поэтому при недоступном Redis чтения падают в БД, а не
 * в HTTP 500, и записи не срываются.</p>
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    /** Кэш списков классификаторов (ключ — категория). */
    public static final String CLASSIFIERS_CACHE = "classifiers";
    /** Кэш эффективных политик (ключ — организация|тип ПЛ). */
    public static final String POLICIES_CACHE = "policies";

    /**
     * RedisCacheManager: строковые ключи + JSON-значения, TTL 20 минут.
     * TTL страхует от рассинхронизации, если по какой-то причине инвалидация не сработала
     * (напр. запись прошла в обход сервисного слоя) — устаревшие данные живут не дольше TTL.
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(20))
                .disableCachingNullValues() // null не кэшируем — чтобы «дырки» не заслоняли реальные данные
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(jsonRedisSerializer()));
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }

    /**
     * JSON-сериализатор значений. Доступ к полям — по полям (а не геттерам/сеттерам):
     * доменные сущности (Classifier, Policy) не имеют сеттера для id, поэтому только
     * полевой доступ гарантирует корректный round-trip. JavaTimeModule нужен для
     * OffsetDateTime в Policy; активированная типизация (@class) — чтобы Jackson знал
     * конкретный тип при десериализации значения кэша.
     */
    private GenericJackson2JsonRedisSerializer jsonRedisSerializer() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);
        return new GenericJackson2JsonRedisSerializer(mapper);
    }

    /**
     * Грациозная деградация: ошибки Redis (недоступен/таймаут) логируем и игнорируем.
     * - чтение (get): проглатываем → Spring вызывает реальный метод и берёт данные из БД;
     * - запись/инвалидация (put/evict/clear): проглатываем → сам бизнес-запрос не срывается.
     * Ограниченность staleness обеспечивает TTL кэша.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.warn("Redis-кэш недоступен при чтении '{}' key={} — читаем из БД: {}",
                        cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                log.warn("Redis-кэш недоступен при записи '{}' key={}: {}",
                        cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                log.warn("Redis-кэш недоступен при инвалидации '{}' key={}: {}",
                        cache.getName(), key, e.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                log.warn("Redis-кэш недоступен при очистке '{}': {}", cache.getName(), e.getMessage());
            }
        };
    }
}
