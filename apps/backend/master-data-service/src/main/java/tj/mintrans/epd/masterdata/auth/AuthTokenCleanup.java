package tj.mintrans.epd.masterdata.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.masterdata.repository.AuthRefreshTokenRepository;

import java.time.OffsetDateTime;

/**
 * Ночная уборка одноразовых ключей входа (токены обновления, ключи смены пароля и второго
 * шага). Просроченный ключ предъявить уже нельзя, но запись остаётся: каждый вход и каждое
 * обновление сессии (раз в ~30 минут у каждого вошедшего) добавляют строку, и без уборки
 * таблица растёт без предела. Сутки запаса — чтобы разбор инцидента «вчера» ещё видел ключи.
 */
@Component
public class AuthTokenCleanup {

    private static final Logger log = LoggerFactory.getLogger(AuthTokenCleanup.class);

    private final AuthRefreshTokenRepository tokens;

    public AuthTokenCleanup(AuthRefreshTokenRepository tokens) {
        this.tokens = tokens;
    }

    @Scheduled(cron = "${epd.auth.token-cleanup-cron:0 30 3 * * *}", zone = "${epd.auth.token-cleanup-zone:Asia/Dushanbe}")
    @Transactional
    public void removeExpired() {
        int removed = tokens.deleteExpired(OffsetDateTime.now().minusDays(1));
        if (removed > 0) {
            log.info("Уборка ключей входа: удалено просроченных записей — {}", removed);
        }
    }
}
