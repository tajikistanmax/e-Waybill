package tj.mintrans.epd.waybill.print;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIGRATION.md §2.25 — «Рамз» (сквозной номер груза {@code cargos.number}) в печати борхата:
 * из вложенного снимка груза, иначе из снимка накладной {@code typeData.cargoNumber}, иначе «—».
 */
class WaybillPrintServiceCargoNumberTest {

    @Test
    @DisplayName("номер из снимка груза typeData.cargo.number имеет приоритет")
    void fromCargoSnapshot() {
        Map<String, Object> cargo = Map.of("name", "Цемент", "number", 17);
        Map<String, Object> td = Map.of("cargoNumber", 99);

        assertThat(WaybillPrintService.cargoNumberOf(cargo, td)).isEqualTo("17");
    }

    @Test
    @DisplayName("иначе — снимок накладной typeData.cargoNumber (в JSONB может прийти как 42.0 → «42»)")
    void fromConsignmentSnapshot() {
        Map<String, Object> td = new HashMap<>();
        td.put("cargoNumber", 42L);
        assertThat(WaybillPrintService.cargoNumberOf(Map.of(), td)).isEqualTo("42");

        td.put("cargoNumber", 42.0d);
        assertThat(WaybillPrintService.cargoNumberOf(null, td)).isEqualTo("42");
    }

    @Test
    @DisplayName("номера нет — прочерк")
    void dashWhenAbsent() {
        assertThat(WaybillPrintService.cargoNumberOf(Map.of("name", "Песок"), Map.of())).isEqualTo("—");
        assertThat(WaybillPrintService.cargoNumberOf(null, null)).isEqualTo("—");
    }
}
