import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;

/**
 * ТЕСТОВЫЙ Крипто-сервис Удостоверяющего центра (ИМИТАЦИЯ, НЕ для прода).
 *
 * Реализует тот же контракт, что ждёт CadesTitleSigner в режиме epd.signing.mode=cades:
 *   POST {crypto-url}/api/v1/sign  {waybillId, titleType, signerRma, data}
 *     -> { "signature": "<base64>", "algorithm": ..., "format": ..., "signedAt": ..., "signerRma": ... }
 *
 * Ставит НАСТОЯЩУЮ ECDSA-P256 подпись (SHA256withECDSA) над телом запроса — проверяемую
 * публичным ключом, — имитируя квалифицированную ЭЦП (CAdES-BES/T) боевого УЦ РТ. Так весь
 * процесс подписания титулов Т1–Т6 проверяется end-to-end до появления реального Crypto Service.
 *
 * Дополнительно печатает на старте QR_SIGNING_KEY=<EC JWK> — стабильный ключ подписи QR
 * (в проде выдаётся из HSM/Vault; здесь генерируется), чтобы waybill-service стартовал в
 * режиме cades (иначе fail-fast требует стабильный ключ QR).
 *
 * Запуск:  javac CryptoServiceMock.java  &&  java CryptoServiceMock [порт=9099]
 * Зависимостей нет — только JDK (com.sun.net.httpserver).
 */
public class CryptoServiceMock {

    static KeyPair signingKey;

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9099;

        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        signingKey = g.generateKeyPair();

        // Ключ подписи QR (второй EC-ключ) — печатаем как приватный JWK для QR_SIGNING_KEY.
        KeyPair qr = g.generateKeyPair();
        System.out.println("QR_SIGNING_KEY=" + ecPrivateJwk((ECPublicKey) qr.getPublic(), (ECPrivateKey) qr.getPrivate(), "epd-mock-qr"));

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/v1/sign", CryptoServiceMock::handleSign);
        server.createContext("/", ex -> respond(ex, 200, "{\"service\":\"crypto-service-mock\",\"status\":\"UP\"}"));
        server.setExecutor(null);
        server.start();
        System.out.println("CRYPTO-MOCK: тестовый крипто-сервис УЦ на порту " + port + " (POST /api/v1/sign). НЕ для прода.");
    }

    static void handleSign(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"method not allowed\"}");
            return;
        }
        byte[] body = ex.getRequestBody().readAllBytes();
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(signingKey.getPrivate());
            s.update(body);
            String signature = Base64.getEncoder().encodeToString(s.sign());
            String json = new String(body, StandardCharsets.UTF_8);
            String signerRma = extract(json, "signerRma");
            String titleType = extract(json, "titleType");
            System.out.println("CRYPTO-MOCK: подписан титул " + titleType + " signerRma=" + signerRma + " (" + body.length + " байт)");
            String resp = "{\"signature\":\"" + signature + "\","
                    + "\"algorithm\":\"SHA256withECDSA\","
                    + "\"format\":\"CAdES-BES/T (mock)\","
                    + "\"signedAt\":\"" + Instant.now() + "\","
                    + "\"signerRma\":\"" + signerRma + "\"}";
            respond(ex, 200, resp);
        } catch (Exception e) {
            respond(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    /** Плоское извлечение строкового поля из JSON (тест-сервис, без парсер-зависимостей). */
    static String extract(String json, String field) {
        int i = json.indexOf("\"" + field + "\"");
        if (i < 0) return "";
        int colon = json.indexOf(':', i);
        if (colon < 0) return "";
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return "";
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return "";
        return json.substring(q1 + 1, q2);
    }

    static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    static String b64url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** Число в фиксированные len байт (big-endian, с левым дополнением нулями). */
    static byte[] fixed(BigInteger v, int len) {
        byte[] b = v.toByteArray();
        if (b.length == len) return b;
        byte[] out = new byte[len];
        if (b.length > len) System.arraycopy(b, b.length - len, out, 0, len);
        else System.arraycopy(b, 0, out, len - b.length, b.length);
        return out;
    }

    /** EC P-256 приватный ключ в формате JWK (для epd.qr.signing-key). */
    static String ecPrivateJwk(ECPublicKey pub, ECPrivateKey priv, String kid) {
        byte[] x = fixed(pub.getW().getAffineX(), 32);
        byte[] y = fixed(pub.getW().getAffineY(), 32);
        byte[] d = fixed(priv.getS(), 32);
        return "{\"kty\":\"EC\",\"crv\":\"P-256\",\"kid\":\"" + kid + "\","
                + "\"x\":\"" + b64url(x) + "\",\"y\":\"" + b64url(y) + "\",\"d\":\"" + b64url(d) + "\"}";
    }
}
