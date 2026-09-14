package tj.mintrans.epd.masterdata.config;

import org.junit.jupiter.api.Test;
import tj.mintrans.epd.masterdata.domain.Organization;
import tj.mintrans.epd.masterdata.repository.OrganizationRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Область видимости арендатора ({@link TenantScope}) — изоляция филиалов.
 *
 * <p>Второй найденный вручную класс проблем — «кто что видит». Здесь закреплено:
 * администратор компании видит компанию и все её филиалы, администратор филиала —
 * только свой, платформенная роль не ограничена, тенант без claim не видит ничего.</p>
 */
class TenantScopeTest {

    private static Organization org(String rma) {
        var o = new Organization();
        o.setRma(rma);
        return o;
    }

    private CurrentUser user(boolean tenantScoped, String ownRma, String... roles) {
        var u = mock(CurrentUser.class);
        lenient().when(u.isTenantScoped()).thenReturn(tenantScoped);
        lenient().when(u.organizationRma()).thenReturn(Optional.ofNullable(ownRma));
        for (String r : List.of("COMPANY_ADMIN", "BRANCH_ADMIN", "DISPATCHER", "SYSTEM_ADMIN")) {
            lenient().when(u.hasRole(r)).thenReturn(List.of(roles).contains(r));
        }
        return u;
    }

    @Test
    void companyAdminSeesCompanyAndAllBranches() {
        var repo = mock(OrganizationRepository.class);
        when(repo.findByParentRma("100002000")).thenReturn(List.of(org("100002091"), org("100002092")));
        var scope = new TenantScope(user(true, "100002000", "COMPANY_ADMIN"), repo);

        assertThat(scope.isBounded()).isTrue();
        assertThat(scope.rmas()).containsExactlyInAnyOrder("100002000", "100002091", "100002092");
        assertThat(scope.contains("100002091")).isTrue();   // свой филиал виден
        assertThat(scope.contains("999999999")).isFalse();  // чужая организация — нет
        assertThat(scope.canWrite("100002092")).isTrue();
    }

    @Test
    void branchAdminSeesOnlyOwnBranch() {
        var repo = mock(OrganizationRepository.class);
        var scope = new TenantScope(user(true, "100002091", "BRANCH_ADMIN"), repo);

        assertThat(scope.rmas()).containsExactly("100002091");
        assertThat(scope.contains("100002000")).isFalse();  // головная компания не видна филиалу
        assertThat(scope.contains("100002091")).isTrue();
    }

    @Test
    void dispatcherSeesOnlyOwnOrganization() {
        var repo = mock(OrganizationRepository.class);
        var scope = new TenantScope(user(true, "025680800", "DISPATCHER"), repo);

        assertThat(scope.rmas()).containsExactly("025680800");
        assertThat(scope.canWrite("100002000")).isFalse();  // чужая организация — запись запрещена
    }

    @Test
    void platformRoleIsNotBounded() {
        var repo = mock(OrganizationRepository.class);
        var scope = new TenantScope(user(false, null, "SYSTEM_ADMIN"), repo);

        assertThat(scope.isBounded()).isFalse();
        assertThat(scope.rmas()).isEmpty();                 // область не ограничена
        assertThat(scope.contains("любая-организация")).isTrue();
        assertThat(scope.canWrite("любая-организация")).isTrue();
    }

    @Test
    void tenantWithoutClaimSeesNothing() {
        var repo = mock(OrganizationRepository.class);
        var scope = new TenantScope(user(true, null, "DISPATCHER"), repo);

        assertThat(scope.rmas()).containsExactly("__none__");
        assertThat(scope.contains("025680800")).isFalse();
    }
}
