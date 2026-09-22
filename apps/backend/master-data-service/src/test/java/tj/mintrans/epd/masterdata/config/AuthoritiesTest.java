package tj.mintrans.epd.masterdata.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Состав ролей, читающих справочники парка и персонала (блок A5 сквозной приёмки 22.09.2026).
 * Тест закрепляет два требования: внешние кабинеты накладных доступа не имеют, а рабочие места
 * и межсервисный канал — имеют (иначе выдача ПЛ, осмотры и печать молча отвалятся с 403).
 */
class AuthoritiesTest {

    @Test
    @DisplayName("внешние кабинеты накладных не читают парк и персонал перевозчика")
    void externalCabinetsExcluded() {
        assertThat(Authorities.REGISTRY_READ)
                .doesNotContain("CLIENT_SENDER")
                .doesNotContain("CLIENT_FORWARDER")
                .doesNotContain("CUSTOMS_OFFICER");
    }

    @Test
    @DisplayName("рабочие места и межсервисный канал сохраняют доступ")
    void workplacesAndServiceChannelIncluded() {
        assertThat(Authorities.REGISTRY_READ).contains(
                "SYSTEM_ADMIN", "API_INTEGRATOR", "MINTRANS_ANALYST", "INSPECTOR",
                "COMPANY_ADMIN", "BRANCH_ADMIN", "DISPATCHER",
                "DOCTOR", "MECHANIC", "DRIVER", "ACCOUNTANT", "FUEL_STATION");
    }

    @Test
    @DisplayName("выражение — список ролей, а не отрицание (новая роль по умолчанию без доступа)")
    void expressionIsAllowListNotDenyList() {
        assertThat(Authorities.REGISTRY_READ).startsWith("hasAnyRole(").doesNotContain("!");
    }
}
