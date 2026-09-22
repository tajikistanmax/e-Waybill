package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.service.RefChannelRules.Form;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MIGRATION.md 9.8 — правила legacy Requests/kvd/Store*Request 1-в-1 и маппинг полей в typeData. */
class RefChannelRulesTest {

    private static Map<String, Object> base(String driverKey) {
        Map<String, Object> m = new HashMap<>();
        m.put("organization_rma", "025680800");
        m.put("transport_registration_number", "0114TJ01");
        m.put(driverKey, "461930031");
        return m;
    }

    @Test
    void formsMapToWaybillTypesAndLegacyCodes() {
        assertThat(Form.parse("waybill1ad")).isEqualTo(Form.WAYBILL1AD);
        assertThat(Form.parse("WAYBILL5BBM")).isEqualTo(Form.WAYBILL5BBM);
        assertThat(Form.parse("waybill9")).isNull();
        assertThat(Form.byCode(4)).isEqualTo(Form.WAYBILL3C);
        assertThat(Form.byCode(7)).isNull();
        assertThat(Form.WAYBILL3C.types()).containsExactlyInAnyOrder(WaybillType.WB_CAR, WaybillType.WB_TAXI);
        assertThat(Form.ofType(WaybillType.WB_TROLLEYBUS)).isEqualTo(Form.WAYBILL1ADE);
        assertThat(Form.WAYBILL2B.primary()).isEqualTo(WaybillType.WB_TRUCK);
    }

    @Test
    void waybill1adRequiresEmployeeAndCapsFuels() {
        Map<String, Object> m = base("driver_rma");
        m.put("exit_date", "06:30");
        assertThatThrownBy(() -> RefChannelRules.validateStore(Form.WAYBILL1AD, m))
                .isInstanceOf(UnprocessableException.class).hasMessage(RefChannelRules.MSG_EMPLOYEE);
        m.put("employee_rma", "333333333");
        m.put("fuels", List.of(Map.of("fuel_id", 1, "fuel_given", 10, "remain_fuel_before_exit", 1),
                Map.of("fuel_id", 2, "fuel_given", 10, "remain_fuel_before_exit", 1),
                Map.of("fuel_id", 3, "fuel_given", 10, "remain_fuel_before_exit", 1)));
        assertThatThrownBy(() -> RefChannelRules.validateStore(Form.WAYBILL1AD, m))
                .hasMessage("Максимальное количество видов топлива - 2.");
        m.put("fuels", List.of(Map.of("fuel_id", 2, "fuel_given", 10, "remain_fuel_before_exit", 1, "additional", 6)));
        assertThatThrownBy(() -> RefChannelRules.validateStore(Form.WAYBILL1AD, m)).hasMessage(RefChannelRules.MSG_ADDITIONAL);
        m.put("fuels", List.of(Map.of("fuel_id", 4, "fuel_given", 10, "remain_fuel_before_exit", 1)));
        assertThatThrownBy(() -> RefChannelRules.validateStore(Form.WAYBILL1AD, m)).hasMessage(RefChannelRules.MSG_FUEL_ID);
        m.put("fuels", List.of(Map.of("fuel_id", 2, "fuel_given", 10, "remain_fuel_before_exit", 1, "additional", 5)));
        m.put("begin_path_a", "somewhere");
        assertThatThrownBy(() -> RefChannelRules.validateStore(Form.WAYBILL1AD, m)).hasMessage(RefChannelRules.MSG_BEGIN_PATH);
        m.put("begin_path_a", "begin_path_b");
        m.put("conditioner_time", "01:30");
        RefChannelRules.validateStore(Form.WAYBILL1AD, m);
        Map<String, Object> td = RefChannelRules.typeData(Form.WAYBILL1AD, m);
        assertThat(td).containsEntry("beginPathA", "begin_path_b").containsEntry("exitTime", "06:30");
        assertThat((BigDecimal) td.get("conditionerHours")).isEqualByComparingTo("1.5");
    }

    @Test
    void waybill3cRouteRequiredForRouteService() {
        Map<String, Object> m = base("driver_rma");
        m.put("type_service", 4);
        assertThatThrownBy(() -> RefChannelRules.validateStore(Form.WAYBILL3C, m)).hasMessage(RefChannelRules.MSG_TYPE_SERVICE);
        m.put("type_service", 2);
        assertThatThrownBy(() -> RefChannelRules.validateStore(Form.WAYBILL3C, m))
                .hasMessage("Поле route_id обязательно при type_service = 2.");
        m.put("route_id", "3");
        m.put("schedule", "1");
        m.put("regions_id", List.of("1", "8"));
        assertThatThrownBy(() -> RefChannelRules.validateStore(Form.WAYBILL3C, m)).hasMessage("regions_id: допустимые значения 1–7.");
        m.put("regions_id", List.of("1", "2"));
        m.put("work_days", List.of(Map.of("date", "2026-09-22", "laps", 3)));
        assertThatThrownBy(() -> RefChannelRules.validateStore(Form.WAYBILL3C, m))
                .hasMessage("Поле begin_path_a обязательно при type_service = 2.");
        m.put("work_days", List.of(Map.of("date", "2026-09-22", "begin_path_a", "begin_path_a", "begin_path_b", "begin_path_b", "laps", 3)));
        RefChannelRules.validateStore(Form.WAYBILL3C, m);
        Map<String, Object> td = RefChannelRules.typeData(Form.WAYBILL3C, m);
        assertThat(td).containsEntry("serviceKind", "ROUTE").containsEntry("typeService", "2");
        assertThat(td.get("workRegions")).isEqualTo(List.of(1, 2));
    }

    @Test
    void waybill2bAnd5bbmRules() {
        Map<String, Object> m = base("driver_rma");
        m.put("type_of_shipment", 3);
        assertThatThrownBy(() -> RefChannelRules.validateStore(Form.WAYBILL2B, m)).hasMessage(RefChannelRules.MSG_SHIPMENT);
        m.put("type_of_shipment", 2);
        assertThatThrownBy(() -> RefChannelRules.validateStore(Form.WAYBILL2B, m)).hasMessage("Параметр direction_id обязателен.");
        m.put("direction_id", "5");
        m.put("client_id", "c1");
        m.put("work_days", List.of());
        assertThatThrownBy(() -> RefChannelRules.validateStore(Form.WAYBILL2B, m)).hasMessage("Минимальное количество рабочих дней - 1.");
        m.put("work_days", List.of(Map.of("date", "2026-09-22", "exit_time", "08:00", "entry_time", "17:00",
                "indication_counter_exit", 100, "indication_counter_entry", 250,
                "fuels", List.of(Map.of("fuel_id", 2, "fuel_given", 80, "remain_fuel_before_exit", 20),
                        Map.of("fuel_id", 1, "fuel_given", 8, "remain_fuel_before_exit", 2)))));
        assertThatThrownBy(() -> RefChannelRules.validateStore(Form.WAYBILL2B, m)).hasMessage("Максимальное количество видов топлива - 1.");
        m.put("work_days", List.of(Map.of("date", "2026-09-22", "exit_time", "08:00", "entry_time", "17:00",
                "indication_counter_exit", 100, "indication_counter_entry", 250)));
        m.put("trailers", List.of(Map.of("registration_number", "tr01", "brand", "Kogel", "carrying", 20)));
        RefChannelRules.validateStore(Form.WAYBILL2B, m);
        Map<String, Object> td = RefChannelRules.typeData(Form.WAYBILL2B, m);
        assertThat(td).containsEntry("shipmentKind", "HOURLY").containsEntry("directionId", "5");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trailers = (List<Map<String, Object>>) td.get("trailers");
        assertThat(trailers.get(0)).containsEntry("registrationNumber", "TR01").containsEntry("brand", "Kogel");

        Map<String, Object> intl = base("first_driver_rma");
        intl.put("load_country_name", "Tajikistan");
        intl.put("load_city_name", "Dushanbe");
        intl.put("unload_country_name", "Uzbekistan");
        intl.put("unload_city_name", "Tashkent");
        intl.put("cargo_id", "c-1");
        assertThatThrownBy(() -> RefChannelRules.validateStore(Form.WAYBILL5BBM, intl)).hasMessage(RefChannelRules.MSG_BBA);
        intl.put("bba_number", "BBA-1");
        intl.put("visa_country_name", "Uzbekistan");
        intl.put("visa_expire_date", "2027-01-01");
        intl.put("permit_number", "QA3PERMIT001");
        intl.put("transit_countries_name", List.of("Kazakhstan"));
        intl.put("arrival_time", "2026-09-25 10:00:00");
        RefChannelRules.validateStore(Form.WAYBILL5BBM, intl);
        Map<String, Object> itd = RefChannelRules.typeData(Form.WAYBILL5BBM, intl);
        assertThat(itd).containsEntry("loadCountry", "Tajikistan").containsEntry("bbaNumber", "BBA-1")
                .containsEntry("visaValidTo", "2027-01-01").containsEntry("permitNumber", "QA3PERMIT001")
                .containsEntry("arrivalTime", "2026-09-25T10:00:00");
        assertThat(itd.get("transitCountries")).isEqualTo(List.of("Kazakhstan"));
        // driver_rma vs first_driver_rma — сообщение legacy
        assertThatThrownBy(() -> RefChannelRules.validateStore(Form.WAYBILL5BBM, base("driver_rma"))).hasMessage(RefChannelRules.MSG_FIRST_DRIVER);
        assertThatThrownBy(() -> RefChannelRules.validateStore(Form.WAYBILL1A, Map.of("organization_rma", "12345"))).hasMessage(RefChannelRules.MSG_ORG);
    }
}
