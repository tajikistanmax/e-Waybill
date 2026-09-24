package tj.mintrans.epd.masterdata.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * Одноразовые коды второго фактора (TOTP, RFC 6238): 6 цифр, шаг 30 секунд, HMAC-SHA1.
 *
 * <p>Это параметры по умолчанию всех распространённых приложений-аутентификаторов (Google
 * Authenticator, Microsoft Authenticator, FreeOTP, Яндекс Ключ) и прежней настройки Keycloak,
 * поэтому любое из них подходит без настройки. Реализация своя — отдельная библиотека ради
 * тридцати строк не нужна, а внешних зависимостей у контура входа меньше.</p>
 */
public final class Totp {

    public static final int DIGITS = 6;
    public static final int STEP_SECONDS = 30;
    /**
     * Сколько соседних шагов принимается: ±1 (±30 с) — на расхождение часов телефона и сервера
     * и на время ввода. Больше не берём: каждый лишний шаг облегчает подбор.
     */
    public static final int WINDOW = 1;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {
    }

    /** Новый секрет: 160 бит (как рекомендует RFC 4226), в Base32 — 32 символа. */
    public static String newSecret() {
        byte[] raw = new byte[20];
        RANDOM.nextBytes(raw);
        return base32(raw);
    }

    /**
     * Ссылка для QR-кода приложения-аутентификатора. {@code issuer} — подпись в приложении
     * («е-Роҳхат»), чтобы пользователь с несколькими записями видел, какая наша.
     */
    public static String otpauthUri(String issuer, String account, String secret) {
        String label = enc(issuer) + ":" + enc(account);
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + enc(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    /** Номер шага времени для момента {@code at}. */
    public static long step(Instant at) {
        return Math.floorDiv(at.getEpochSecond(), STEP_SECONDS);
    }

    /**
     * Проверка кода. Возвращает номер шага, которому код соответствует, или -1.
     *
     * <p>{@code lastUsedStep} — шаг последнего принятого кода: этот и более ранние не
     * принимаются, иначе подсмотренный код можно было бы предъявить второй раз в те же
     * полминуты.</p>
     */
    public static long verify(String secret, String code, Instant now, Long lastUsedStep) {
        if (secret == null || code == null) {
            return -1;
        }
        String digits = code.replaceAll("\\s", "");
        if (!digits.matches("\\d{" + DIGITS + "}")) {
            return -1;
        }
        byte[] key = base32Decode(secret);
        long current = step(now);
        long found = -1;
        // Проверяем все шаги окна (без раннего выхода) — время ответа не зависит от того,
        // какой из шагов совпал.
        for (long s = current - WINDOW; s <= current + WINDOW; s++) {
            boolean match = MessageDigest.isEqual(
                    code(key, s).getBytes(StandardCharsets.US_ASCII),
                    digits.getBytes(StandardCharsets.US_ASCII));
            if (match && (lastUsedStep == null || s > lastUsedStep) && found < 0) {
                found = s;
            }
        }
        return found;
    }

    /** Код для шага {@code step} (RFC 4226, динамическое усечение). */
    static String code(byte[] key, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = h[h.length - 1] & 0x0f;
            int binary = ((h[offset] & 0x7f) << 24)
                    | ((h[offset + 1] & 0xff) << 16)
                    | ((h[offset + 2] & 0xff) << 8)
                    | (h[offset + 3] & 0xff);
            int otp = binary % 1_000_000;
            return String.format("%06d", otp);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось вычислить код второго фактора", e);
        }
    }

    static String base32(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 0x1f));
        }
        return out.toString();
    }

    static byte[] base32Decode(String text) {
        String s = text.replace("=", "").replace(" ", "").toUpperCase();
        ByteBuffer out = ByteBuffer.allocate(s.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (char c : s.toCharArray()) {
            int v = BASE32.indexOf(c);
            if (v < 0) {
                throw new IllegalArgumentException("Секрет второго фактора повреждён");
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) ((buffer >> (bits - 8)) & 0xff));
                bits -= 8;
            }
        }
        return out.array();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
