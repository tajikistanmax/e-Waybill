package tj.mintrans.epd.waybill.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.domain.Malumotnoma;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.repository.MalumotnomaRepository;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Старые бумажные QR «Роҳхат» (legacy {@code /qrcode/{type}/{encrypt(id)}}, QrCodeController; сверка 25.09, G5).
 * После переключения на e-Waybill эти адреса давали 404, а на руках — листы 3-С «30» (до 30 дней), 5Б-БМ
 * и др. Токен — Laravel {@code encrypt()}: base64(JSON {iv, value, mac}), AES-256-CBC, HMAC-SHA256 от
 * «iv+value» ключом APP_KEY старой системы (секрет, в git нет — переменная {@code LEGACY_APP_KEY}).
 * Перенесённый лист ищется по номеру {@code MG{код формы}{legacy id}} (так их пронумеровала миграция Ф5).
 */
@Service
public class LegacyQrService {

    /** Тип QR legacy → код таблицы перенесённых листов (typeData.legacyTable). 6–7 (борхат, СМР) не переносились. */
    static final Map<String, String> TYPE_TO_TABLE = Map.of("1", "1D", "2", "1A", "3", "3C", "4", "2B", "5", "5F");
    /** Тип 8 — справка (маълумотнома): перенесена с номером = legacy id (run_malumotnoma.ps1). */
    static final String TYPE_MALUMOTNOMA = "8";
    private static final Pattern PHP_INT = Pattern.compile("^i:(\\d+);$");
    private static final Pattern PHP_STR = Pattern.compile("^s:\\d+:\"(\\d+)\";$");

    private final byte[] key;
    private final WaybillRepository waybills;
    private final MalumotnomaRepository malumotnomas;
    private final QrTokenService qr;
    private final ObjectMapper json = new ObjectMapper();

    public LegacyQrService(@Value("${epd.legacy.app-key:}") String appKey, WaybillRepository waybills,
                           MalumotnomaRepository malumotnomas, QrTokenService qr) {
        this.key = parseKey(appKey);
        this.waybills = waybills;
        this.malumotnomas = malumotnomas;
        this.qr = qr;
    }

    static byte[] parseKey(String appKey) {
        if (appKey == null || appKey.isBlank()) {
            return null;
        }
        String k = appKey.trim();
        return k.startsWith("base64:") ? Base64.getDecoder().decode(k.substring(7)) : k.getBytes(StandardCharsets.UTF_8);
    }

    public boolean configured() {
        return key != null;
    }

    /** Токен проверки e-Waybill (как в QR новых бланков) для старого бумажного QR. */
    @Transactional(readOnly = true)
    public String resolve(String type, String token) {
        if (!configured()) {
            throw new UnprocessableException("Проверка QR-кодов старой системы не настроена (нет ключа LEGACY_APP_KEY)");
        }
        if (TYPE_MALUMOTNOMA.equals(type)) {
            long legacyId = decrypt(token).orElseThrow(() -> new UnprocessableException("QR-код старой системы недействителен"));
            Malumotnoma m = malumotnomas.findByNumber(legacyId).filter(Malumotnoma::isLegacy)
                    .orElseThrow(() -> new NotFoundException("Справка старой системы не найдена среди перенесённых"));
            return qr.sign(m);
        }
        String table = TYPE_TO_TABLE.get(type);
        if (table == null) {
            throw new NotFoundException("Документы этого вида (борхат, СМР) из старой системы не переносились — "
                    + "проверьте путевой лист по его номеру");
        }
        long legacyId = decrypt(token).orElseThrow(() -> new UnprocessableException("QR-код старой системы недействителен"));
        Waybill wb = waybills.findByNumber("MG" + table + legacyId)
                .orElseThrow(() -> new NotFoundException("Путевой лист старой системы не найден среди перенесённых"));
        return qr.signLegacy(wb);
    }

    /** Laravel {@code decrypt()} → числовой id; пусто — подделка, чужой ключ или повреждённый токен. */
    Optional<Long> decrypt(String token) {
        try {
            JsonNode p = json.readTree(Base64.getDecoder().decode(token.replace('-', '+').replace('_', '/')));
            String iv = p.path("iv").asText(), value = p.path("value").asText(), mac = p.path("mac").asText();
            if (iv.isEmpty() || value.isEmpty() || mac.isEmpty()) {
                return Optional.empty();
            }
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] expected = HexFormat.of().formatHex(hmac.doFinal((iv + value).getBytes(StandardCharsets.UTF_8)))
                    .getBytes(StandardCharsets.US_ASCII);
            if (!MessageDigest.isEqual(expected, mac.getBytes(StandardCharsets.US_ASCII))) {
                return Optional.empty();
            }
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(Base64.getDecoder().decode(iv)));
            String plain = new String(c.doFinal(Base64.getDecoder().decode(value)), StandardCharsets.UTF_8);
            Matcher m = PHP_INT.matcher(plain);
            if (m.matches()) {
                return Optional.of(Long.parseLong(m.group(1)));
            }
            m = PHP_STR.matcher(plain);
            return m.matches() ? Optional.of(Long.parseLong(m.group(1))) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
