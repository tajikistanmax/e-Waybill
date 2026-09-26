package tj.mintrans.epd.waybill.print;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.client.MasterDataClient;
import tj.mintrans.epd.waybill.domain.Malumotnoma;
import tj.mintrans.epd.waybill.domain.MalumotnomaLine;
import tj.mintrans.epd.waybill.service.MalumotnomaService;
import tj.mintrans.epd.waybill.service.QrTokenService;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Печатный бланк справки пассажиру (маълумотнома) в PDF — письмо A4 на таджикском, как в «Роҳхат»
 * (сверка 25.09, B7): шапка ведомства, «аз {сана} № {рақам}», «Ба шаҳрванд», ссылка на Нархнома,
 * директор, QR проверки, исполнитель.
 */
@Service
public class MalumotnomaPrintService {

    /** Вид транспорта в тексте письма (legacy transport_type.name, строчными). */
    private static final Map<Short, String> TYPE_NAME_TJ = Map.of(
            (short) 1, "автобус", (short) 3, "микроавтобус", (short) 4, "автомобили сабукрав");
    /** Родительный падеж месяца, как в legacy config('trans.month') + «и». */
    private static final String[] MONTH_TJ = {"январи", "феврали", "марти", "апрели", "майи", "июни",
            "июли", "августи", "сентябри", "октябри", "ноябри", "декабри"};

    // Реквизиты шапки и директор по умолчанию — текст бланка «Роҳхат»; действуют, пока в /settings
    // нет своих значений (пустое значение в настройках скрывает строку).
    static final String DEFAULT_ORG_TITLE = "МУАССИСАИ ДАВЛАТИИ «НАҚЛИЁТИ АВТОМОБИЛӢ ВА ХИЗМАТРАСОНИИ ЛОГИСТИКӢ»";
    static final String DEFAULT_ORG_CONTACTS = "734042, ш. Душанбе, к. Айнӣ, 14а Тел (372) 222-23-22; 222-20-57 "
            + "E-mail: tajiklogistics2022@gmail.com";
    static final String DEFAULT_DIRECTOR = "Ашурзода Ф.";
    /** Архивные справки «Роҳхат», выданные до 10.03.2025, подписаны прежним директором (как печатает legacy). */
    private static final LocalDate DIRECTOR_CHANGED = LocalDate.of(2025, 3, 10);
    private static final String DIRECTOR_BEFORE = "Саломзода Р.С.";

    private final MalumotnomaService service;
    private final PdfRenderService pdf;
    private final MasterDataClient masterData;
    private final QrTokenService qrToken;
    private final QrImageService qrImage;
    private final String publicBaseUrl;
    private final String emblem;

    public MalumotnomaPrintService(MalumotnomaService service, PdfRenderService pdf, MasterDataClient masterData,
                                   QrTokenService qrToken, QrImageService qrImage,
                                   @Value("${epd.public-base-url:http://localhost:3000}") String publicBaseUrl) {
        this.service = service;
        this.pdf = pdf;
        this.masterData = masterData;
        this.qrToken = qrToken;
        this.qrImage = qrImage;
        this.publicBaseUrl = publicBaseUrl;
        this.emblem = classpathDataUri("print-assets/nishon.png", "image/png");
    }

    @Transactional(readOnly = true)
    public byte[] renderPdf(UUID id) {
        return pdf.render("print/malumotnoma", model(service.get(id)));
    }

    Map<String, Object> model(Malumotnoma m) {
        Map<String, String> printSettings = masterData.printSettings();
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("number", m.getNumber());
        model.put("fio", m.getFio());
        ZonedDateTime issued = PrintZone.local(m.getCreatedAt());
        model.put("dateText", issued == null ? "—" : String.format("%02d %s соли %d",
                issued.getDayOfMonth(), MONTH_TJ[issued.getMonthValue() - 1], issued.getYear()));
        model.put("transportType", TYPE_NAME_TJ.getOrDefault(m.getTransportTypeId(), "—"));
        model.put("privileged", m.getAge() == 1);
        model.put("price", money(m.getPrice()));

        List<String> parts = new ArrayList<>();
        for (MalumotnomaLine l : m.getLines()) {
            parts.add(l.getRoute().getName().trim() + (l.isRoundTrip() ? " (сафари рафту баргашт)" : ""));
        }
        model.put("routeStr", parts.isEmpty() ? (m.getRouteSummary() == null ? "—" : m.getRouteSummary())
                : String.join(", ", parts));

        model.put("issuer", m.getIssuerName() != null ? m.getIssuerName()
                : (m.getIssuerRma() != null ? m.getIssuerRma() : "—"));
        model.put("orgTitle", setting(printSettings, "malumotnoma_org_title", DEFAULT_ORG_TITLE));
        model.put("orgContacts", setting(printSettings, "malumotnoma_org_contacts", DEFAULT_ORG_CONTACTS));
        String director = setting(printSettings, "malumotnoma_director", DEFAULT_DIRECTOR);
        if (m.isLegacy() && issued != null && issued.toLocalDate().isBefore(DIRECTOR_CHANGED)) {
            director = DIRECTOR_BEFORE;
        }
        model.put("director", director);
        // Реквизиты приказа об утверждении нархномы — настраиваются в /settings (SYSTEM_ADMIN);
        // пусто — фраза «тибқи …» не печатается.
        String tariffOrder = printSettings.get("malumotnoma_tariff_order");
        model.put("tariffOrder", tariffOrder == null ? "" : tariffOrder.trim());
        model.put("emblem", emblem);
        // Печать организации кассира не ставится: письмо — от имени учреждения из шапки, а не перевозчика.

        model.put("annulled", m.isAnnulled());
        model.put("annulledAt", m.isAnnulled() ? PrintZone.dateTime(m.getAnnulledAt()) : "");
        model.put("annulReason", m.getAnnulReason() == null ? "" : m.getAnnulReason());

        // Водяной знак и отметка о формировании — те же настройки категории print, что и у бланков ПЛ.
        model.put("generatedAt", PrintZone.now());
        model.put("showWatermark", "true".equalsIgnoreCase(printSettings.getOrDefault("show_watermark", "false")));
        String watermarkText = printSettings.get("watermark_text");
        model.put("watermarkText", watermarkText == null ? "" : watermarkText.trim());

        // QR проверки — как у ПЛ (JWS ES256 + /verify); в «Роҳхат» — /qrcode/8/{encrypt(id)}.
        model.put("qr", qrImage.dataUri(publicBaseUrl + "/verify/" + qrToken.sign(m)));
        return model;
    }

    /** Сумма без лишних нулей, как на бланке «Роҳхат»: 392, 98.5. */
    static String money(BigDecimal v) {
        if (v == null) {
            return "0";
        }
        BigDecimal s = v.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }

    private static String setting(Map<String, String> s, String key, String dflt) {
        String v = s.get(key);
        return v == null ? dflt : v.trim();
    }

    private static String classpathDataUri(String path, String mime) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(in.readAllBytes());
        } catch (IOException e) {
            return null;
        }
    }
}
