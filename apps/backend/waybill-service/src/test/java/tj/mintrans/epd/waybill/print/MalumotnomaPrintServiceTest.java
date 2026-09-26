package tj.mintrans.epd.waybill.print;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tj.mintrans.epd.waybill.client.MasterDataClient;
import tj.mintrans.epd.waybill.domain.Malumotnoma;
import tj.mintrans.epd.waybill.domain.MalumotnomaLine;
import tj.mintrans.epd.waybill.domain.MalumotnomaRoute;
import tj.mintrans.epd.waybill.service.MalumotnomaService;
import tj.mintrans.epd.waybill.service.QrTokenService;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Бланк маълумотнома как письмо «Роҳхат» (сверка 25.09, B7): дата по-таджикски, номер, директор, реквизиты. */
class MalumotnomaPrintServiceTest {

    private final MasterDataClient md = mock(MasterDataClient.class);
    private final QrTokenService qr = mock(QrTokenService.class);
    private final QrImageService qrImage = mock(QrImageService.class);
    private final MalumotnomaPrintService print = new MalumotnomaPrintService(
            mock(MalumotnomaService.class), mock(PdfRenderService.class), md, qr, qrImage, "https://verify.test");

    private static Malumotnoma sample(OffsetDateTime created, boolean legacy) {
        Malumotnoma m = new Malumotnoma();
        m.setNumber(53803L);
        m.setFio("Ахмедов Сафар");
        m.setTransportTypeId((short) 4);
        m.setPrice(new BigDecimal("392.00"));
        m.setIssuerName("Салимов С.");
        ReflectionTestUtils.setField(m, "createdAt", created);
        ReflectionTestUtils.setField(m, "legacy", legacy);
        MalumotnomaRoute r = new MalumotnomaRoute();
        r.setName("ш. Душанбе - ш. Хуҷанд  ");
        MalumotnomaLine l = new MalumotnomaLine();
        l.setRoute(r);
        l.setRoundTrip(true);
        m.addLine(l);
        return m;
    }

    @Test
    @DisplayName("дата «аз 26 сентябри соли 2026» по времени Душанбе, самт с «сафари рафту баргашт», вид строчными")
    void letterFields() {
        when(md.printSettings()).thenReturn(Map.of("malumotnoma_tariff_order", " Нархномаи №13 "));
        when(qr.sign(any(Malumotnoma.class))).thenReturn("jws");
        // 25.09 21:30 UTC = 26.09 02:30 в Душанбе.
        Map<String, Object> model = print.model(sample(OffsetDateTime.of(2026, 9, 25, 21, 30, 0, 0, ZoneOffset.UTC), false));

        assertThat(model.get("dateText")).isEqualTo("26 сентябри соли 2026");
        assertThat(model.get("number")).isEqualTo(53803L);
        assertThat(model.get("routeStr")).isEqualTo("ш. Душанбе - ш. Хуҷанд (сафари рафту баргашт)");
        assertThat(model.get("transportType")).isEqualTo("автомобили сабукрав");
        assertThat(model.get("price")).isEqualTo("392");
        assertThat(model.get("tariffOrder")).isEqualTo("Нархномаи №13");
        assertThat(model.get("director")).isEqualTo(MalumotnomaPrintService.DEFAULT_DIRECTOR);
        assertThat(model.get("orgTitle")).isEqualTo(MalumotnomaPrintService.DEFAULT_ORG_TITLE);
        assertThat(model.get("emblem")).asString().startsWith("data:image/png;base64,");
    }

    @Test
    @DisplayName("архивная справка до 10.03.2025 — прежний директор; настройка с пустым значением скрывает строку")
    void directorAndSettings() {
        when(md.printSettings()).thenReturn(Map.of("malumotnoma_director", "Нов Н.", "malumotnoma_org_contacts", ""));
        when(qr.sign(any(Malumotnoma.class))).thenReturn("jws");
        Map<String, Object> old = print.model(sample(OffsetDateTime.of(2024, 5, 1, 8, 0, 0, 0, ZoneOffset.UTC), true));
        assertThat(old.get("director")).isEqualTo("Саломзода Р.С.");
        assertThat(old.get("orgContacts")).isEqualTo("");
        Map<String, Object> fresh = print.model(sample(OffsetDateTime.of(2024, 5, 1, 8, 0, 0, 0, ZoneOffset.UTC), false));
        assertThat(fresh.get("director")).isEqualTo("Нов Н.");
    }

    @Test
    @DisplayName("сумма без лишних нулей: 392.00 → 392, 98.50 → 98.5")
    void money() {
        assertThat(MalumotnomaPrintService.money(new BigDecimal("392.00"))).isEqualTo("392");
        assertThat(MalumotnomaPrintService.money(new BigDecimal("98.50"))).isEqualTo("98.5");
        assertThat(MalumotnomaPrintService.money(new BigDecimal("1000"))).isEqualTo("1000");
    }
}
