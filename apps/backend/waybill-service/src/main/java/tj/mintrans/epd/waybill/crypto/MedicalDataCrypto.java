package tj.mintrans.epd.waybill.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

/**
 * Шифрование медицинских показателей осмотра (пульс, давление, алкотест и т.п. —
 * особая категория данных, ИБ-13.1.2/13.1.3/13.4.2) перед записью в JSONB-колонку
 * {@code waybill_title.data}. AES-256-GCM, ключ — 256-битный, из хранилища секретов
 * (пока — переменная окружения/infra/.env; перспектива Vault/HSM без смены API,
 * см. ИБ-13.4.4 — тот же подход, что и у {@code QrTokenService}).
 *
 * <p>Обязателен (без dev-заглушки, в отличие от подписи титулов): в отличие от
 * юридической значимости ЭП, отсутствие шифрования особой категории ПДн — это не
 * «сгодится для демо», а прямая утечка при компрометации БД в любой среде.</p>
 */
@Component
public class MedicalDataCrypto {

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecretKeySpec key;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public MedicalDataCrypto(@Value("${epd.meddata.encryption-key:}") String keyBase64, ObjectMapper mapper) {
        this.mapper = mapper;
        if (keyBase64 == null || keyBase64.isBlank()) {
            throw new IllegalStateException(
                    "epd.meddata.encryption-key не задан: шифрование медицинских показателей "
                    + "обязательно (особая категория ПДн, ИБ-13.1.3). Сгенерируйте 256-битный ключ "
                    + "(напр. openssl rand -base64 32) и задайте через переменную MEDDATA_ENCRYPTION_KEY.");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(keyBase64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("epd.meddata.encryption-key не является корректным Base64", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException(
                    "epd.meddata.encryption-key должен быть 256-битным ключом (32 байта после Base64-декодирования), "
                    + "получено " + raw.length + " байт");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /** {@code null}/пустая карта → {@code null} (нечего шифровать, титул без показателей). */
    public String encryptToBase64(Map<String, Object> plain) {
        if (plain == null || plain.isEmpty()) {
            return null;
        }
        try {
            byte[] plaintext = mapper.writeValueAsBytes(plain);
            byte[] iv = new byte[GCM_IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);
            ByteBuffer buf = ByteBuffer.allocate(iv.length + ciphertext.length);
            buf.put(iv).put(ciphertext);
            return Base64.getEncoder().encodeToString(buf.array());
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Не удалось зашифровать медицинские показатели", e);
        }
    }

    /**
     * Расшифровка для регламентированного доступа (ИБ-13.1.3: медработники своей
     * организации, уполномоченные лица Минздрава/Минтранса — с фиксацией в аудите).
     * На сегодня без вызывающей стороны в коде — задел на будущий эндпоинт;
     * см. spec/notes/05-соответствие-стандартам-АГВ.md.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> decryptFromBase64(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("Не удалось расшифровать медицинские показатели: пустое значение");
        }
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            if (all.length <= GCM_IV_BYTES) {
                throw new IllegalStateException(
                        "Не удалось расшифровать медицинские показатели: данные короче IV (повреждены)");
            }
            byte[] iv = Arrays.copyOfRange(all, 0, GCM_IV_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(all, GCM_IV_BYTES, all.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return mapper.readValue(plaintext, Map.class);
        } catch (IllegalStateException e) {
            throw e;
        } catch (IllegalArgumentException | GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Не удалось расшифровать медицинские показатели", e);
        }
    }
}
