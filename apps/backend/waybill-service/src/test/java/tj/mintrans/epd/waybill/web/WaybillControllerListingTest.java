package tj.mintrans.epd.waybill.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tj.mintrans.epd.waybill.calc.WaybillCalcAssembler;
import tj.mintrans.epd.waybill.config.CurrentUser;
import tj.mintrans.epd.waybill.config.SecurityConfig;
import tj.mintrans.epd.waybill.config.TenantScope;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.repository.WaybillStatusEventRepository;
import tj.mintrans.epd.waybill.repository.WaybillTitleRepository;
import tj.mintrans.epd.waybill.service.FuelCalculationService;
import tj.mintrans.epd.waybill.service.QrTokenService;
import tj.mintrans.epd.waybill.service.WaybillRegistryService;
import tj.mintrans.epd.waybill.service.WaybillService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Легаси-листинг {@code GET /api/v1/waybills} (найдено 23.10.2026 при устранении искусственных
 * лимитов): признак усечения в заголовке {@value WaybillController#TRUNCATED_HEADER} вместо
 * молчаливой потери строк, и честный 403 вместо пустого массива, когда у тенант-роли нет claim
 * {@code organization_rma} (ошибка привязки учётной записи, не «у организации нечего показать»).
 *
 * <p>{@code epd.registry.list-cap=3} — маленький лимит, чтобы граница была достижима без 1000 записей.</p>
 */
@WebMvcTest(WaybillController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "epd.registry.list-cap=3")
class WaybillControllerListingTest {

    @Autowired MockMvc mvc;

    @MockitoBean WaybillService service;
    @MockitoBean WaybillRepository waybills;
    @MockitoBean WaybillTitleRepository titles;
    @MockitoBean WaybillStatusEventRepository events;
    @MockitoBean QrTokenService qr;
    @MockitoBean FuelCalculationService fuelCalculation;
    @MockitoBean WaybillCalcAssembler waybillCalc;
    @MockitoBean CurrentUser currentUser;
    @MockitoBean TenantScope tenantScope;
    @MockitoBean WaybillRegistryService registry;
    @MockitoBean tj.mintrans.epd.waybill.service.FuelBalanceService fuelBalance;
    @MockitoBean JwtDecoder jwtDecoder;

    private static RequestPostProcessor as(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static List<Waybill> stubs(int n) {
        List<Waybill> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new Waybill());
        return out;
    }

    @Test
    void emptyOrganizationScope_isForbidden_notEmptyList() throws Exception {
        when(tenantScope.isBounded()).thenReturn(true);
        when(tenantScope.rmas()).thenReturn(Set.of("__none__"));

        mvc.perform(get("/api/v1/waybills").with(as("DISPATCHER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("organization_rma")));
    }

    @Test
    void resultAtCap_setsTruncatedHeader() throws Exception {
        when(tenantScope.isBounded()).thenReturn(true);
        when(tenantScope.rmas()).thenReturn(Set.of("025680800"));
        when(currentUser.hasRole("DRIVER")).thenReturn(false);
        // listCap=3 (свойство теста) — репозиторий «вернул» ровно cap строк, как БД при реальном усечении.
        when(waybills.findByOrganizationRmaInAndSourceNotOrderByCreatedAtDesc(eq(Set.of("025680800")), eq("MIGRATED"), any()))
                .thenReturn(stubs(3));

        mvc.perform(get("/api/v1/waybills").with(as("DISPATCHER")))
                .andExpect(status().isOk())
                .andExpect(header().string(WaybillController.TRUNCATED_HEADER, "true"))
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void resultBelowCap_noTruncatedHeader() throws Exception {
        when(tenantScope.isBounded()).thenReturn(true);
        when(tenantScope.rmas()).thenReturn(Set.of("025680800"));
        when(currentUser.hasRole("DRIVER")).thenReturn(false);
        when(waybills.findByOrganizationRmaInAndSourceNotOrderByCreatedAtDesc(eq(Set.of("025680800")), eq("MIGRATED"), any()))
                .thenReturn(stubs(1)); // 1 < listCap=3 — выборка точно не усечена

        mvc.perform(get("/api/v1/waybills").with(as("DISPATCHER")))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(WaybillController.TRUNCATED_HEADER))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void driverFilterAppliedAfterCapCheck_doesNotHideTruncation() throws Exception {
        // Водитель видит часть организации — но усечение считается ДО фильтра по водителю:
        // если БД отдала ровно cap строк, по organization-level это усечение, даже если после
        // фильтра по своим ПЛ у водителя осталась всего 1 строка.
        when(tenantScope.isBounded()).thenReturn(true);
        when(tenantScope.rmas()).thenReturn(Set.of("025680800"));
        when(currentUser.hasRole("DRIVER")).thenReturn(true);
        when(currentUser.rma()).thenReturn(Optional.of("461930031"));
        var driverWb = new Waybill();
        driverWb.setDriverRma("461930031");
        var full = new ArrayList<Waybill>();
        full.add(driverWb);
        full.add(new Waybill());
        full.add(new Waybill()); // 3 = listCap, значит организация-уровень усечён
        when(waybills.findByOrganizationRmaInAndSourceNotOrderByCreatedAtDesc(eq(Set.of("025680800")), eq("MIGRATED"), any()))
                .thenReturn(full);

        mvc.perform(get("/api/v1/waybills").with(as("DRIVER")))
                .andExpect(status().isOk())
                .andExpect(header().string(WaybillController.TRUNCATED_HEADER, "true"))
                .andExpect(jsonPath("$.length()").value(1)); // после фильтра по своим ПЛ остался 1
    }
}
