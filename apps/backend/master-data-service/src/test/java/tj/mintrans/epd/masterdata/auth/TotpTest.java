package tj.mintrans.epd.masterdata.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Коды второго фактора — по эталонным значениям RFC 6238 (приложение B, SHA-1). */
class TotpTest {

    /** Секрет из RFC: ASCII «12345678901234567890». */
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void base32MatchesRfcSecret() {
        assertThat(Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII))).isEqualTo(RFC_SECRET);
        assertThat(new String(Totp.base32Decode(RFC_SECRET), StandardCharsets.US_ASCII)).isEqualTo("12345678901234567890");
    }

    @Test
    void codesMatchRfcVectors() {
        byte[] key = Totp.base32Decode(RFC_SECRET);
        // В RFC коды 8-значные; 6-значный код — их последние шесть цифр.
        assertThat(Totp.code(key, Totp.step(Instant.ofEpochSecond(59)))).isEqualTo("287082");
        assertThat(Totp.code(key, Totp.step(Instant.ofEpochSecond(1111111109)))).isEqualTo("081804");
        assertThat(Totp.code(key, Totp.step(Instant.ofEpochSecond(1234567890)))).isEqualTo("005924");
        assertThat(Totp.code(key, Totp.step(Instant.ofEpochSecond(2000000000)))).isEqualTo("279037");
    }

    @Test
    void acceptsNeighbourStepAndRejectsFarOne() {
        Instant now = Instant.ofEpochSecond(1234567890);
        byte[] key = Totp.base32Decode(RFC_SECRET);
        long step = Totp.step(now);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(key, step), now, null)).isEqualTo(step);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(key, step - 1), now, null)).isEqualTo(step - 1);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(key, step + 1), now, null)).isEqualTo(step + 1);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(key, step - 3), now, null)).isEqualTo(-1);
    }

    @Test
    void sameCodeIsNotAcceptedTwice() {
        Instant now = Instant.ofEpochSecond(1234567890);
        String code = Totp.code(Totp.base32Decode(RFC_SECRET), Totp.step(now));
        long used = Totp.verify(RFC_SECRET, code, now, null);
        assertThat(used).isPositive();
        assertThat(Totp.verify(RFC_SECRET, code, now, used)).isEqualTo(-1);
    }

    @Test
    void rejectsMalformedInput() {
        Instant now = Instant.now();
        assertThat(Totp.verify(RFC_SECRET, "12345", now, null)).isEqualTo(-1);
        assertThat(Totp.verify(RFC_SECRET, "abcdef", now, null)).isEqualTo(-1);
        assertThat(Totp.verify(RFC_SECRET, null, now, null)).isEqualTo(-1);
        assertThat(Totp.verify(null, "123456", now, null)).isEqualTo(-1);
    }

    @Test
    void newSecretIs160BitsAndUriIsWellFormed() {
        String secret = Totp.newSecret();
        assertThat(secret).hasSize(32).matches("[A-Z2-7]+");
        assertThat(Totp.base32Decode(secret)).hasSize(20);
        assertThat(Totp.otpauthUri("e-Rohkhat", "admin", secret))
                .startsWith("otpauth://totp/e-Rohkhat:admin?secret=" + secret)
                .contains("issuer=e-Rohkhat", "digits=6", "period=30");
    }
}
