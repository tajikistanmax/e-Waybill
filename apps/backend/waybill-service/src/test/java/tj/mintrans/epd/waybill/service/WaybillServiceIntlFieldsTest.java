package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIGRATION.md §12.10 — обязательные поля 5Б-БМ по legacy {@code kvd/StoreWaybill5bbmRequest}:
 * груз, города погрузки/разгрузки, номер ББА (страны/виза/дозвол проверяются отдельно в {@code create}).
 */
class WaybillServiceIntlFieldsTest {

    private static Map<String, Object> full() {
        Map<String, Object> d = new HashMap<>();
        d.put("cargoName", "Цемент");
        d.put("loadCity", "Душанбе");
        d.put("unloadCity", "Ташкент");
        d.put("bbaNumber", "BBA-2026-0001");
        return d;
    }

    @Test
    @DisplayName("все обязательные поля заданы — без ошибок")
    void allPresent() {
        assertThatCode(() -> WaybillService.requireTruckIntlFields(full())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("каждое из полей cargoName / loadCity / unloadCity / bbaNumber обязательно (пусто или пробелы → 422)")
    void eachRequired() {
        for (String field : new String[]{"cargoName", "loadCity", "unloadCity", "bbaNumber"}) {
            Map<String, Object> d = full();
            d.put(field, "  ");
            assertThatThrownBy(() -> WaybillService.requireTruckIntlFields(d))
                    .as(field).isInstanceOf(UnprocessableException.class).hasMessageContaining(field);
            d.remove(field);
            assertThatThrownBy(() -> WaybillService.requireTruckIntlFields(d))
                    .as(field + " (absent)").isInstanceOf(UnprocessableException.class);
        }
    }
}
