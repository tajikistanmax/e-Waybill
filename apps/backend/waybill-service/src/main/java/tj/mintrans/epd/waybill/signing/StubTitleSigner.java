package tj.mintrans.epd.waybill.signing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Dev-подпись (по умолчанию): SHA-256 хеш содержимого титула. НЕ является
 * юридически значимой ЭП — только целостность в dev. Для прода включить
 * квалифицированную ЭП: epd.signing.mode=cades ({@link CadesTitleSigner}).
 */
@Component
@ConditionalOnProperty(name = "epd.signing.mode", havingValue = "stub", matchIfMissing = true)
public class StubTitleSigner implements TitleSigner {

    @Override
    public String sign(UUID waybillId, String titleType, String signerRma, Map<String, Object> data) {
        String payload = waybillId + titleType + signerRma + String.valueOf(data);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
