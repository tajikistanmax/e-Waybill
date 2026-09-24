package tj.mintrans.epd.masterdata.auth;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tj.mintrans.epd.masterdata.repository.AuthRefreshTokenRepository;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuthTokenCleanupTest {

    /** Удаляются только ключи, истёкшие больше суток назад: свежие нужны для разбора инцидентов. */
    @Test
    void removesKeysExpiredMoreThanADayAgo() {
        var repo = mock(AuthRefreshTokenRepository.class);
        new AuthTokenCleanup(repo).removeExpired();
        var before = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repo).deleteExpired(before.capture());
        assertThat(before.getValue()).isBetween(
                OffsetDateTime.now().minusDays(1).minusMinutes(1), OffsetDateTime.now().minusDays(1).plusMinutes(1));
    }
}
