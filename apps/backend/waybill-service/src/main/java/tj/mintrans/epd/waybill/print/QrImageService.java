package tj.mintrans.epd.waybill.print;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/**
 * QR-код проверки ПЛ для печатного бланка — {@code data:image/png;base64,...}
 * (обращение PDF-движка во внешнюю сеть недопустимо).
 *
 * <p>Перенос {@code tj.etrans.rohkhat.print.QrCodeService}. Содержимое кода —
 * ссылка на публичную страницу офлайн-проверки {@code {base}/verify/{jws}}
 * (тот же JWS ES256, что и в мобильном приложении инспектора).</p>
 */
@Service
public class QrImageService {

    private static final Logger log = LoggerFactory.getLogger(QrImageService.class);
    // 240px давал ненадёжное декодирование реальных (не тестовых) JWS-нагрузок — реальный
    // подписанный ES256 JWS ~490 символов (номер, тип, ТС, водитель, организация, допуски,
    // exp/nbf) кодируется в QR-код версии, которая при таком размере растра на границе
    // распознавания: проверено 2026-09-04 прогоном jsQR (тот же движок, что в сканере
    // /inspector) по РЕАЛЬНОМУ выданному JWS — 200px не распознаётся вообще, 240px
    // не проверялся отдельно, но лежит в той же опасной зоне, 250px+ — стабильно. Взят
    // запас сверх порога на случай более длинных payload'ов в будущем (доп. claims).
    private static final int SIZE_PX = 320;

    /** Изображение QR-кода для строки; {@code null} при ошибке кодирования (бланк печатается и без кода). */
    public String dataUri(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, SIZE_PX, SIZE_PX, hints);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (WriterException | IOException e) {
            log.warn("QR-код для бланка не построен: {}", e.getMessage());
            return null;
        }
    }
}
