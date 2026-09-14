package tj.mintrans.epd.waybill.print;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.client.MasterDataClient;
import tj.mintrans.epd.waybill.domain.Malumotnoma;
import tj.mintrans.epd.waybill.domain.MalumotnomaLine;
import tj.mintrans.epd.waybill.service.MalumotnomaService;
import tj.mintrans.epd.waybill.service.QrTokenService;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Печатный бланк справки пассажиру (маълумотнома) в PDF.
 */
@Service
public class MalumotnomaPrintService {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final Map<Short, String> TYPE_NAME = Map.of(
            (short) 1, "Автобус", (short) 3, "Микроавтобус", (short) 4, "Легковой автомобиль");

    private final MalumotnomaService service;
    private final PdfRenderService pdf;
    private final MasterDataClient masterData;
    private final QrTokenService qrToken;
    private final QrImageService qrImage;
    private final String publicBaseUrl;

    public MalumotnomaPrintService(MalumotnomaService service, PdfRenderService pdf, MasterDataClient masterData,
                                   QrTokenService qrToken, QrImageService qrImage,
                                   @Value("${epd.public-base-url:http://localhost:3000}") String publicBaseUrl) {
        this.service = service;
        this.pdf = pdf;
        this.masterData = masterData;
        this.qrToken = qrToken;
        this.qrImage = qrImage;
        this.publicBaseUrl = publicBaseUrl;
    }

    @Transactional(readOnly = true)
    public byte[] renderPdf(UUID id) {
        Malumotnoma m = service.get(id);
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("id", m.getId().toString());
        model.put("fio", m.getFio());
        model.put("transportType", TYPE_NAME.getOrDefault(m.getTransportTypeId(), "—"));
        model.put("privileged", m.getAge() == 1);
        model.put("issuedAt", m.getCreatedAt() != null ? DT.format(m.getCreatedAt()) : "—");
        model.put("issuer", m.getIssuerName() != null ? m.getIssuerName()
                : (m.getIssuerRma() != null ? m.getIssuerRma() : "—"));
        model.put("orgRma", m.getOrganizationRma() != null ? m.getOrganizationRma() : "—");
        model.put("price", m.getPrice());
        // Реквизиты приказа об утверждении нархномы — настраиваются в /settings (SYSTEM_ADMIN),
        // не зашиты в код: номер приказа реален и специфичен, платформа не может его выдумать.
        // Пусто, пока администратор не внесёт актуальный приказ — тогда строка не печатается.
        Map<String, String> printSettings = masterData.printSettings();
        String tariffOrder = printSettings.get("malumotnoma_tariff_order");
        model.put("tariffOrder", tariffOrder == null ? "" : tariffOrder.trim());

        // Водяной знак и отметка о формировании (B4, НЕ-ЭЦП часть) — те же настройки
        // категории print, что и у бланков ПЛ (WaybillPrintService), чтобы справка не
        // оставалась без футера «Сформировано: …» и водяного знака.
        model.put("generatedAt", DT.format(java.time.LocalDateTime.now()));
        model.put("showWatermark", "true".equalsIgnoreCase(printSettings.getOrDefault("show_watermark", "false")));
        String watermarkText = printSettings.get("watermark_text");
        model.put("watermarkText", watermarkText == null ? "" : watermarkText.trim());

        // QR-код проверки — в оригинале ссылка на страницу проверки по зашифрованному id
        // (qr/malumotnoma.blade.php); здесь тот же механизм, что и у ПЛ (JWS ES256 + /verify).
        model.put("qr", qrImage.dataUri(publicBaseUrl + "/verify/" + qrToken.sign(m)));

        List<Map<String, Object>> lines = new ArrayList<>();
        for (MalumotnomaLine l : m.getLines()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("route", l.getRoute().getName());
            row.put("roundTrip", l.isRoundTrip());
            row.put("price", l.getRoute().priceFor(m.getTransportTypeId())
                    .multiply(java.math.BigDecimal.valueOf(l.isRoundTrip() ? 2 : 1)));
            lines.add(row);
        }
        model.put("lines", lines);
        return pdf.render("print/malumotnoma", model);
    }
}
