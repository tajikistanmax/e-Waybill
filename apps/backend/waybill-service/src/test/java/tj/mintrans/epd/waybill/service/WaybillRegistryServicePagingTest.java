package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import tj.mintrans.epd.waybill.config.CurrentUser;
import tj.mintrans.epd.waybill.config.TenantScope;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.service.WaybillRegistryService.Filter;
import tj.mintrans.epd.waybill.web.error.ApiErrors;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIGRATION.md §8.4 — пагинация реестра: лимит РАЗМЕРА страницы (конфигурируемый, не общего числа
 * записей) и честный отказ 403 вместо пустого реестра при отсутствии claim organization_rma
 * (найдено 23.10.2026 при устранении искусственных ограничений выборок).
 */
class WaybillRegistryServicePagingTest {

    private static final Filter EMPTY_FILTER =
            new Filter(null, null, null, null, null, null, null, null, null, null, null, false);

    private WaybillRepository waybills;
    private TenantScope tenantScope;
    private CurrentUser currentUser;

    private WaybillRegistryService service(int maxPageSize) {
        waybills = mock(WaybillRepository.class);
        tenantScope = mock(TenantScope.class);
        currentUser = mock(CurrentUser.class);
        return new WaybillRegistryService(waybills, tenantScope, currentUser, maxPageSize);
    }

    @Test
    @DisplayName("пустая область (claim organization_rma отсутствует): page() — 403, не пустой PageResult")
    void pageForbiddenWhenNoOrganizationClaim() {
        var svc = service(1000);
        when(tenantScope.isBounded()).thenReturn(true);
        when(tenantScope.rmas()).thenReturn(Set.of("__none__"));

        assertThatThrownBy(() -> svc.page(EMPTY_FILTER, 0, 20))
                .isInstanceOf(ApiErrors.ForbiddenException.class)
                .hasMessageContaining("organization_rma");
    }

    @Test
    @DisplayName("пустая область: statusCounts() — тоже 403, а не нулевые счётчики")
    void statusCountsForbiddenWhenNoOrganizationClaim() {
        var svc = service(1000);
        when(tenantScope.isBounded()).thenReturn(true);
        when(tenantScope.rmas()).thenReturn(Set.of("__none__"));

        assertThatThrownBy(() -> svc.statusCounts(null))
                .isInstanceOf(ApiErrors.ForbiddenException.class);
    }

    @Test
    @DisplayName("платформенная роль (область не ограничена) с заявленным organizationRma — доступ не 403")
    void platformRoleWithExplicitOrgIsNotForbidden() {
        var svc = service(1000);
        when(tenantScope.isBounded()).thenReturn(false);
        when(waybills.findAll(org.mockito.ArgumentMatchers.<Specification<Waybill>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        var result = svc.page(EMPTY_FILTER, 0, 20);
        assertThat(result.totalElements()).isZero();
    }

    @Test
    @DisplayName("граница размера страницы: ?size= больше настроенного максимума — обрезается до него (защита от OOM)")
    void pageSizeClampedToConfiguredMax() {
        var svc = service(5); // maxPageSize=5 — делаем лимит маленьким, чтобы тест был детерминирован
        when(tenantScope.isBounded()).thenReturn(false);
        var captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        when(waybills.findAll(org.mockito.ArgumentMatchers.<Specification<Waybill>>any(), captor.capture()))
                .thenReturn(new PageImpl<>(List.of()));

        svc.page(EMPTY_FILTER, 0, 999); // клиент запросил 999 — лимит сервиса 5
        assertThat(captor.getValue().getPageSize()).isEqualTo(5);

        svc.page(EMPTY_FILTER, 0, 3); // запрошено МЕНЬШЕ лимита — проходит как есть
        assertThat(captor.getValue().getPageSize()).isEqualTo(3);

        svc.page(EMPTY_FILTER, 0, 0); // size=0/отрицательный — не ноль строк, минимум 1
        assertThat(captor.getValue().getPageSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("отрицательный номер страницы — приводится к 0, а не передаётся в Pageable как есть")
    void negativePageClampedToZero() {
        var svc = service(1000);
        when(tenantScope.isBounded()).thenReturn(false);
        var captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        when(waybills.findAll(org.mockito.ArgumentMatchers.<Specification<Waybill>>any(), captor.capture()))
                .thenReturn(new PageImpl<>(List.of()));

        svc.page(EMPTY_FILTER, -5, 20);
        assertThat(captor.getValue().getPageNumber()).isZero();
    }

    @Test
    @DisplayName("страница ЗА последней: репозиторий (как и реальная БД) вернул бы пустой content — PageResult честно отражает это, не 1000-строчный обман")
    void pageBeyondLastReturnsEmptyContentWithRealTotals() {
        var svc = service(1000);
        when(tenantScope.isBounded()).thenReturn(false);
        // Смоделировано поведение Spring Data JPA: всего 3 записи, запрошена страница №10 размером 20 —
        // content пуст, но totalElements/totalPages показывают реальный объём (не 0/0, как раньше
        // при scope.none(), и не искусственно урезанный список).
        when(waybills.findAll(org.mockito.ArgumentMatchers.<Specification<Waybill>>any(), any(Pageable.class)))
                .thenAnswer(inv -> {
                    Pageable p = inv.getArgument(1);
                    return new PageImpl<Waybill>(List.of(), p, 3);
                });

        var result = svc.page(EMPTY_FILTER, 10, 20);
        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.page()).isEqualTo(10); // echo запрошенной страницы — фронт понимает, что она пустая
    }
}
