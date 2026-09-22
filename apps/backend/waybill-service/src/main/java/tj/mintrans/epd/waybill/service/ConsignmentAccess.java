package tj.mintrans.epd.waybill.service;

import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillType;

import java.util.Map;
import java.util.Set;

/**
 * Правила доступа внешних кабинетов к накладным (MIGRATION.md 1.1/3.11 — legacy роли
 * {@code client_sender}, {@code client_forwarder}, {@code customs_officer}, {@code RoleTrait::cargoWaybillHasRole}):
 * <ul>
 *   <li>грузоотправитель видит накладные (борхат 2-Б/ОГ и СМР 5Б-БМ), где {@code typeData.senderId} — один из его клиентов;</li>
 *   <li>экспедитор — где {@code typeData.forwarderId} — один из его клиентов;</li>
 *   <li>таможенник — все СМР (5Б-БМ) с заполненной накладной, без привязки к клиентам.</li>
 * </ul>
 */
public final class ConsignmentAccess {

    public static final String ROLE_SENDER = "CLIENT_SENDER";
    public static final String ROLE_FORWARDER = "CLIENT_FORWARDER";
    public static final String ROLE_CUSTOMS = "CUSTOMS_OFFICER";

    private ConsignmentAccess() {
    }

    /** Виды ПЛ, у которых есть накладная. */
    public static boolean hasConsignmentForm(WaybillType type) {
        return type == WaybillType.WB_TRUCK || type == WaybillType.WB_DANGEROUS || type == WaybillType.WB_TRUCK_INTL;
    }

    /** Накладная оформлена: есть отправитель/получатель/экспедитор (как в реестре 8.8). */
    public static boolean hasConsignment(Waybill wb) {
        Map<String, Object> td = wb.getTypeData();
        if (td == null) {
            return false;
        }
        return notBlank(td.get("senderName")) || notBlank(td.get("receiverName")) || notBlank(td.get("forwarderName"));
    }

    /**
     * @param roles     роли пользователя (без префикса ROLE_)
     * @param clientIds клиенты пользователя (UUID в нижнем регистре)
     */
    public static boolean canView(Set<String> roles, Set<String> clientIds, Waybill wb) {
        if (wb == null || !hasConsignmentForm(wb.getWaybillType())) {
            return false;
        }
        Map<String, Object> td = wb.getTypeData() == null ? Map.of() : wb.getTypeData();
        if (roles.contains(ROLE_CUSTOMS) && wb.getWaybillType() == WaybillType.WB_TRUCK_INTL && hasConsignment(wb)) {
            return true;
        }
        if (roles.contains(ROLE_SENDER) && matches(clientIds, td.get("senderId"))) {
            return true;
        }
        return roles.contains(ROLE_FORWARDER) && matches(clientIds, td.get("forwarderId"));
    }

    /** Правка накладной — отправитель/экспедитор своей накладной (legacy: permission update у клиента); таможня — нет. */
    public static boolean canEdit(Set<String> roles, Set<String> clientIds, Waybill wb) {
        if (wb == null) {
            return false;
        }
        Map<String, Object> td = wb.getTypeData() == null ? Map.of() : wb.getTypeData();
        return (roles.contains(ROLE_SENDER) && matches(clientIds, td.get("senderId")))
                || (roles.contains(ROLE_FORWARDER) && matches(clientIds, td.get("forwarderId")));
    }

    /** Таможенное подтверждение — только таможенник и только СМР. */
    public static boolean canConfirmCustoms(Set<String> roles, Waybill wb) {
        return wb != null && roles.contains(ROLE_CUSTOMS) && wb.getWaybillType() == WaybillType.WB_TRUCK_INTL;
    }

    static boolean matches(Set<String> clientIds, Object id) {
        return id != null && !id.toString().isBlank() && clientIds.contains(id.toString().trim().toLowerCase());
    }

    private static boolean notBlank(Object v) {
        return v != null && !v.toString().isBlank();
    }
}
