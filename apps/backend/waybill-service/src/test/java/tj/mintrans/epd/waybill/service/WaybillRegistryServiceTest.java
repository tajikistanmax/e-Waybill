package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.service.WaybillRegistryService.Filter;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIGRATION.md §8.4 — серверная пагинация реестра: разбор параметров отбора (legacy DataTables-фильтры
 * реестров 1-АД/1-А/3-С/2-Б/5Б-БМ/СМР/борхатов). Сама SQL-спецификация проверяется live на стенде.
 */
class WaybillRegistryServiceTest {

    @Test
    @DisplayName("вид обслуживания 3-С: код и числовой legacy-код (TAXI/1, ROUTE/2, HOURLY/3), иное — не фильтруется")
    void svcCodes() {
        assertThat(WaybillRegistryService.svcCodes("TAXI")).containsExactly("TAXI", "1");
        assertThat(WaybillRegistryService.svcCodes("2")).containsExactly("ROUTE", "2");
        assertThat(WaybillRegistryService.svcCodes(" hourly ")).containsExactly("HOURLY", "3");
        assertThat(WaybillRegistryService.svcCodes("")).isNull();
        assertThat(WaybillRegistryService.svcCodes("BUS")).isNull();
    }

    @Test
    @DisplayName("документ: борхат — 2-Б/ОГ, СМР — 5Б-БМ; водитель — РМА (9–10 цифр) или часть ФИО")
    void docKindAndDriver() {
        assertThat(WaybillRegistryService.docKindTypes("attachment")).containsExactlyInAnyOrder(WaybillType.WB_TRUCK, WaybillType.WB_DANGEROUS);
        assertThat(WaybillRegistryService.docKindTypes("CMR")).containsExactly(WaybillType.WB_TRUCK_INTL);
        assertThat(WaybillRegistryService.docKindTypes(null)).isNull();
        assertThat(WaybillRegistryService.isRma("461930031")).isTrue();
        assertThat(WaybillRegistryService.isRma("Иванов")).isFalse();
        assertThat(WaybillRegistryService.norm("  ")).isNull();
        assertThat(WaybillRegistryService.norm(" 0114tj01 ")).isEqualTo("0114tj01");
    }

    @Test
    @DisplayName("активные условия фильтра: пустой отбор = только исключение архива; полный — все условия")
    void activeConditions() {
        Filter empty = new Filter(null, null, null, null, null, null, null, null, null, null, null, false);
        assertThat(WaybillRegistryService.activeConditions(empty)).containsExactly("source<>MIGRATED");
        Filter archivedOnly = new Filter(null, null, null, "", " ", null, "", "", null, null, "", true);
        assertThat(WaybillRegistryService.activeConditions(archivedOnly)).isEmpty();
        Filter full = new Filter("025680800", WaybillStatus.RETURNED, WaybillType.WB_CAR, "0114TJ01", "Иван",
                "TAXI", "attachment", "ООО", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "0000", false);
        assertThat(WaybillRegistryService.activeConditions(full)).containsExactly(
                "source<>MIGRATED", "status", "type", "vehicle", "driver:name", "svc", "docKind", "client", "from", "to", "q");
        Filter byRma = new Filter(null, null, null, null, "461930031", null, null, null, null, null, null, true);
        assertThat(WaybillRegistryService.activeConditions(byRma)).containsExactly("driver:rma");
    }
}
