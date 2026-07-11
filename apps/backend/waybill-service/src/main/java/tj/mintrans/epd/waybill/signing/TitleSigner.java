package tj.mintrans.epd.waybill.signing;

import java.util.Map;
import java.util.UUID;

/**
 * Подпись титула путевого листа. Абстракция позволяет заменить dev-заглушку
 * (хеш содержимого) на квалифицированную электронную подпись (CAdES) через
 * Crypto Service удостоверяющего центра РТ (Закон РТ № 1965) без изменения
 * бизнес-логики — переключением epd.signing.mode (stub|cades).
 */
public interface TitleSigner {

    /** Возвращает строку подписи для сохранения в титуле. */
    String sign(UUID waybillId, String titleType, String signerRma, Map<String, Object> data);
}
