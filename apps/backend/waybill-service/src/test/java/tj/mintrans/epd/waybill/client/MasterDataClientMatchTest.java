package tj.mintrans.epd.waybill.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сопоставление свободного текста листа/карточки ТС со справочниками маршрутов и марок
 * (находка 23.09.2026: «№8 Автовокзал — ТЦ «Садбарг»» и «ЛиАЗ-5292» не находились, в отчётах
 * демо-перевозчиков протяжённость маршрута и норма топлива были нулевыми).
 */
class MasterDataClientMatchTest {

    private static final List<Map<String, Object>> ROUTES = List.of(
            Map.of("id", "other-8", "organizationRma", "111111111", "number", "8", "name", "Чужой маршрут"),
            Map.of("id", "own-8", "organizationRma", "9924011000", "number", "8", "name", "Автовокзал — ТЦ «Садбарг»"),
            Map.of("id", "own-29", "organizationRma", "9924011000", "number", "29", "name", "Зарафшон — Аэропорт"),
            Map.of("id", "mig", "organizationRma", "040000796", "number", "5", "name", "Автовокзал - Зарафшон"));

    private static String id(java.util.Optional<Map<String, Object>> r) {
        return r.map(m -> String.valueOf(m.get("id"))).orElse(null);
    }

    @Test
    @DisplayName("точное название или номер — как раньше (архивные листы: «Автовокзал - Зарафшон»)")
    void exact() {
        assertThat(id(MasterDataClient.matchRoute(ROUTES, "Автовокзал - Зарафшон", "040000796"))).isEqualTo("mig");
        assertThat(id(MasterDataClient.matchRoute(ROUTES, "Автовокзал - Зарафшон", null))).isEqualTo("mig");
        assertThat(id(MasterDataClient.matchRoute(ROUTES, "29", "9924011000"))).isEqualTo("own-29");
    }

    @Test
    @DisplayName("«№8 …» — номер своей организации, а не одноимённый маршрут другого перевозчика")
    void numberPrefixPrefersOwnOrganization() {
        assertThat(id(MasterDataClient.matchRoute(ROUTES, "№8 Автовокзал — ТЦ «Садбарг»", "9924011000"))).isEqualTo("own-8");
        assertThat(id(MasterDataClient.matchRoute(ROUTES, "8 - Автовокзал", "9924011000"))).isEqualTo("own-8");
        assertThat(id(MasterDataClient.matchRoute(ROUTES, "8", "9924011000"))).isEqualTo("own-8");
    }

    @Test
    @DisplayName("название внутри текста — только своя организация; без организации нестрогие правила не применяются")
    void containedNameOwnOnly() {
        assertThat(id(MasterDataClient.matchRoute(ROUTES, "Маршрут Зарафшон — Аэропорт (вечер)", "9924011000"))).isEqualTo("own-29");
        assertThat(MasterDataClient.matchRoute(ROUTES, "№8 Автовокзал — ТЦ «Садбарг»", null)).isEmpty();
        assertThat(MasterDataClient.matchRoute(ROUTES, "Неизвестный", "9924011000")).isEmpty();
    }

    private static final List<Map<String, Object>> BRANDS = List.of(
            Map.of("id", 1, "name", "МАЗ", "model", "103"),
            Map.of("id", 2, "name", "ЛиАЗ", "model", "5292"),
            Map.of("id", 14, "name", "КамАЗ-5320", "model", "5320"));

    @Test
    @DisplayName("марка: точное название, «название-модель» без пробелов/дефисов; префикс без модели — нет")
    void brand() {
        assertThat(MasterDataClient.matchBrand(BRANDS, "камаз-5320")).map(b -> b.get("id")).contains(14);
        assertThat(MasterDataClient.matchBrand(BRANDS, "ЛиАЗ-5292")).map(b -> b.get("id")).contains(2);
        assertThat(MasterDataClient.matchBrand(BRANDS, "ЛиАЗ 5292")).map(b -> b.get("id")).contains(2);
        assertThat(MasterDataClient.matchBrand(BRANDS, "МАЗ")).map(b -> b.get("id")).contains(1);
        assertThat(MasterDataClient.matchBrand(BRANDS, "МАЗ-203")).isEmpty();
    }
}
