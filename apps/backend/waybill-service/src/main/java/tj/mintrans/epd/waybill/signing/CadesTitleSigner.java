package tj.mintrans.epd.waybill.signing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Квалифицированная электронная подпись (CAdES) через Crypto Service удостоверяющего
 * центра РТ (Закон РТ № 1965): титул подписывается ключом организации на HSM, ставится
 * метка доверенного времени (TSP). Активируется epd.signing.mode=cades. В dev неактивен
 * (нет Crypto Service) — скелет интеграции: когда УЦ доступен, включается конфигом.
 *
 * Контракт Crypto Service: POST {crypto-url}/api/v1/sign {waybillId,titleType,signerRma,data}
 * → { "signature": "<CAdES-BES/T base64>" }.
 */
@Component
@ConditionalOnProperty(name = "epd.signing.mode", havingValue = "cades")
public class CadesTitleSigner implements TitleSigner {

    private final RestClient crypto;

    public CadesTitleSigner(@Value("${epd.signing.crypto-url}") String cryptoUrl) {
        // Явные таймауты: подпись титула — на критическом пути осмотров/выдачи ПЛ. Без них
        // зависший Crypto Service УЦ удерживал бы поток Tomcat бесконечно и параллельные
        // подписания исчерпали бы пул. Read=10s — HSM+метка времени TSP медленнее обычного вызова.
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        this.crypto = RestClient.builder().baseUrl(cryptoUrl).requestFactory(factory).build();
    }

    @Override
    public String sign(UUID waybillId, String titleType, String signerRma, Map<String, Object> data) {
        var request = new LinkedHashMap<String, Object>();
        request.put("waybillId", waybillId.toString());
        request.put("titleType", titleType);
        request.put("signerRma", signerRma);
        request.put("data", data);
        Map<String, Object> resp = crypto.post()
                .uri("/api/v1/sign")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        if (resp == null || resp.get("signature") == null) {
            throw new IllegalStateException("Crypto Service УЦ не вернул подпись титула " + titleType);
        }
        return String.valueOf(resp.get("signature"));
    }
}
