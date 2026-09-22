package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillType;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIGRATION.md §1.1/§3.11 — кабинеты внешних пользователей накладных: правила видимости/правки/подтверждения
 * (legacy {@code RoleTrait::cargoWaybillHasRole}, {@code Cargo5bbmCrudController::validatecmr}).
 */
class ConsignmentAccessTest {

    private static final String C1 = "11111111-1111-1111-1111-111111111111";
    private static final String C2 = "22222222-2222-2222-2222-222222222222";

    private static Waybill wb(WaybillType type, String senderId, String forwarderId, boolean withNames) {
        Waybill w = new Waybill();
        w.setWaybillType(type);
        Map<String, Object> td = new HashMap<>();
        if (senderId != null) td.put("senderId", senderId);
        if (forwarderId != null) td.put("forwarderId", forwarderId);
        if (withNames) td.put("senderName", "Sender LLC");
        w.setTypeData(td);
        return w;
    }

    @Test
    @DisplayName("грузоотправитель видит и правит только накладные своих клиентов (senderId), регистр UUID не важен")
    void sender() {
        Set<String> roles = Set.of(ConsignmentAccess.ROLE_SENDER);
        Set<String> mine = Set.of(C1);
        assertThat(ConsignmentAccess.canView(roles, mine, wb(WaybillType.WB_TRUCK, C1.toUpperCase(), null, true))).isTrue();
        assertThat(ConsignmentAccess.canEdit(roles, mine, wb(WaybillType.WB_TRUCK_INTL, C1, null, true))).isTrue();
        assertThat(ConsignmentAccess.canView(roles, mine, wb(WaybillType.WB_TRUCK, C2, null, true))).isFalse();
        assertThat(ConsignmentAccess.canView(roles, mine, wb(WaybillType.WB_TRUCK, null, C1, true))).isFalse();   // он экспедитор, не отправитель
        assertThat(ConsignmentAccess.canView(roles, mine, wb(WaybillType.WB_BUS, C1, null, true))).isFalse();      // у автобуса накладной нет
        assertThat(ConsignmentAccess.canConfirmCustoms(roles, wb(WaybillType.WB_TRUCK_INTL, C1, null, true))).isFalse();
    }

    @Test
    @DisplayName("экспедитор — по forwarderId; таможенник — все СМР с накладной, без клиентов, только подтверждение, без правки")
    void forwarderAndCustoms() {
        Set<String> fwd = Set.of(ConsignmentAccess.ROLE_FORWARDER);
        assertThat(ConsignmentAccess.canView(fwd, Set.of(C2), wb(WaybillType.WB_TRUCK, C1, C2, true))).isTrue();
        assertThat(ConsignmentAccess.canView(fwd, Set.of(C2), wb(WaybillType.WB_TRUCK, C2, null, true))).isFalse();

        Set<String> customs = Set.of(ConsignmentAccess.ROLE_CUSTOMS);
        Waybill cmr = wb(WaybillType.WB_TRUCK_INTL, C1, null, true);
        assertThat(ConsignmentAccess.canView(customs, Set.of(), cmr)).isTrue();
        assertThat(ConsignmentAccess.canConfirmCustoms(customs, cmr)).isTrue();
        assertThat(ConsignmentAccess.canEdit(customs, Set.of(), cmr)).isFalse();
        assertThat(ConsignmentAccess.canView(customs, Set.of(), wb(WaybillType.WB_TRUCK, C1, null, true))).isFalse();     // борхат 2-Б — не таможня
        assertThat(ConsignmentAccess.canView(customs, Set.of(), wb(WaybillType.WB_TRUCK_INTL, null, null, false))).isFalse(); // СМР ещё не оформлена
    }
}
