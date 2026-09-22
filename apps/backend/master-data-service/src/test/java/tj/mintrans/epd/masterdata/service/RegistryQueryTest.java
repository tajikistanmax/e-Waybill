package tj.mintrans.epd.masterdata.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.masterdata.config.TenantScope;
import tj.mintrans.epd.masterdata.domain.Organization;
import tj.mintrans.epd.masterdata.repository.OrganizationRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Область отбора постраничных реестров (ТС / водители / сотрудники): пересечение
 * мультиарендности, выбранной организации и географии. Находка приёмки 22.09.2026 —
 * реестры отдавали всю таблицу (87 МБ ТС / 56 МБ водителей), отбор считался в браузере.
 */
class RegistryQueryTest {

    private final TenantScope tenantScope = mock(TenantScope.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final RegistryQuery query = new RegistryQuery(tenantScope, organizations);

    private final UUID own = UUID.randomUUID();
    private final UUID branch = UUID.randomUUID();
    private final UUID foreign = UUID.randomUUID();

    private Organization org(UUID id, String rma) {
        var o = mock(Organization.class);
        when(o.getId()).thenReturn(id);
        when(o.getRma()).thenReturn(rma);
        return o;
    }

    @Test
    @DisplayName("платформенная роль без фильтров: область не ограничена")
    void platformWithoutFiltersIsUnrestricted() {
        when(tenantScope.isBounded()).thenReturn(false);

        assertThat(query.organizationScope(null, null, null)).isEmpty();
    }

    @Test
    @DisplayName("арендатор видит только свои организации, даже без фильтров")
    void tenantIsAlwaysBounded() {
        when(tenantScope.isBounded()).thenReturn(true);
        when(tenantScope.organizationIds()).thenReturn(List.of(own, branch));

        assertThat(query.organizationScope(null, null, null))
                .contains(List.of(own, branch));
    }

    @Test
    @DisplayName("арендатор не расширяет область, выбрав чужую организацию: выборка пуста")
    void tenantCannotEscapeScopeByRma() {
        when(tenantScope.isBounded()).thenReturn(true);
        when(tenantScope.organizationIds()).thenReturn(List.of(own));
        // Мок собирается в переменную ДО when(...): создание мока внутри аргумента обрывает
        // незавершённое стаббирование (UnfinishedStubbingException).
        var foreignOrg = org(foreign, "999999999");
        when(organizations.findByRma("999999999")).thenReturn(Optional.of(foreignOrg));

        assertThat(query.organizationScope("999999999", null, null)).contains(List.of());
    }

    @Test
    @DisplayName("несуществующий РМА даёт пустую выборку, а не список всех организаций")
    void unknownRmaGivesEmptyScope() {
        when(tenantScope.isBounded()).thenReturn(false);
        when(organizations.findByRma("000000000")).thenReturn(Optional.empty());

        assertThat(query.organizationScope("000000000", null, null)).contains(List.of());
    }

    @Test
    @DisplayName("география сужает область до организаций региона и города")
    void geographyNarrowsScope() {
        when(tenantScope.isBounded()).thenReturn(false);
        when(organizations.findIdsByRegionAndCity(eq((short) 1), any())).thenReturn(List.of(own, branch));

        assertThat(query.organizationScope(null, (short) 1, null)).contains(List.of(own, branch));
    }

    @Test
    @DisplayName("география и арендатор пересекаются: остаётся только своя организация в регионе")
    void geographyIntersectsTenantScope() {
        when(tenantScope.isBounded()).thenReturn(true);
        when(tenantScope.organizationIds()).thenReturn(List.of(own, branch));
        when(organizations.findIdsByRegionAndCity(eq((short) 2), any())).thenReturn(List.of(branch, foreign));

        assertThat(query.organizationScope(null, (short) 2, null)).contains(List.of(branch));
    }

    @Test
    @DisplayName("размер страницы ограничен потолком, отрицательные значения нормализуются")
    void pageableIsBounded() {
        assertThat(query.pageable(-5, 100_000, "name").getPageSize()).isEqualTo(RegistryQuery.MAX_PAGE_SIZE);
        assertThat(query.pageable(-5, 100_000, "name").getPageNumber()).isZero();
        assertThat(query.pageable(2, 0, "name").getPageSize()).isEqualTo(1);
        assertThat(query.pageable(2, 20, "name").getSort().getOrderFor("name")).isNotNull();
    }

    @Test
    @DisplayName("пустой поиск не обращается к справочнику организаций")
    void blankSearchSkipsOrganizationLookup() {
        assertThat(query.organizationIdsByName(null)).isEmpty();
        assertThat(query.organizationIdsByName("   ")).isEmpty();
    }

    @Test
    @DisplayName("поиск по названию компании отдаёт её организации в нижнем регистре")
    void searchByCompanyNameIsCaseInsensitive() {
        when(organizations.findIdsByNameLike("нақлиёт")).thenReturn(List.of(own));

        assertThat(query.organizationIdsByName("  НАҚЛИЁТ ")).containsExactly(own);
    }
}
