package tj.mintrans.epd.waybill.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Потоковый проход по ПЛ периода для отчётов (замена findAll() после Ф5 — 2,3 млн архивных ПЛ):
 * границы периода, предохранитель по объёму, очистка persistence-context, область организаций.
 */
class WaybillPeriodScanTest {

    private WaybillRepository waybills;
    private EntityManager em;

    @BeforeEach
    void setUp() {
        waybills = mock(WaybillRepository.class);
        em = mock(EntityManager.class);
    }

    private static List<Waybill> stubs(int n) {
        List<Waybill> out = new ArrayList<>();
        IntStream.range(0, n).forEach(i -> out.add(new Waybill()));
        return out;
    }

    @Test
    @DisplayName("границы периода: начало дня from включительно, начало следующего за to дня исключительно")
    void bounds() {
        ZoneId z = ZoneId.systemDefault();
        assertThat(WaybillPeriodScan.lower(LocalDate.of(2026, 9, 1)))
                .isEqualTo(LocalDate.of(2026, 9, 1).atStartOfDay(z).toOffsetDateTime());
        assertThat(WaybillPeriodScan.upper(LocalDate.of(2026, 9, 30)))
                .isEqualTo(LocalDate.of(2026, 10, 1).atStartOfDay(z).toOffsetDateTime());
    }

    @Test
    @DisplayName("все организации: считает, стримит по периоду, чистит контекст каждые 500 строк")
    void streamsAndClears() {
        WaybillPeriodScan scan = new WaybillPeriodScan(waybills, em, 100_000);
        when(waybills.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(any(), any())).thenReturn(1200L);
        when(waybills.streamByPeriod(any(), any())).thenReturn(stubs(1200).stream());

        int[] seen = {0};
        scan.forEach(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null, wb -> seen[0]++);

        assertThat(seen[0]).isEqualTo(1200);
        verify(em, times(2)).clear();                       // после 500 и 1000
        verify(waybills, never()).streamByPeriodAndOrganizations(anyCollection(), any(), any());
    }

    @Test
    @DisplayName("область тенанта: стрим по набору организаций; пустая область — ни одного запроса")
    void scopedAndEmptyScope() {
        WaybillPeriodScan scan = new WaybillPeriodScan(waybills, em, 100_000);
        Set<String> scope = Set.of("025680800", "100002091");
        when(waybills.countByOrganizationRmaInAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(eq(scope), any(), any()))
                .thenReturn(3L);
        when(waybills.streamByPeriodAndOrganizations(eq(scope), any(), any())).thenReturn(stubs(3).stream());

        int[] seen = {0};
        scan.forEach(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 21), scope, wb -> seen[0]++);
        assertThat(seen[0]).isEqualTo(3);

        int[] none = {0};
        scan.forEach(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 21), Set.of(), wb -> none[0]++);
        assertThat(none[0]).isZero();
        verify(waybills, never()).streamByPeriod(any(), any());
    }

    @Test
    @DisplayName("предохранитель: больше лимита строк в периоде → 422 с подсказкой сузить период, стрим не открывается")
    void refusesOversizedPeriod() {
        WaybillPeriodScan scan = new WaybillPeriodScan(waybills, em, 1000);
        when(waybills.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(any(), any())).thenReturn(2_283_786L);

        assertThatThrownBy(() -> scan.forEach(LocalDate.of(2020, 1, 1), LocalDate.of(2026, 12, 31), null, wb -> { }))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("2283786")
                .hasMessageContaining("лимит 1000");
        verify(waybills, never()).streamByPeriod(any(), any());
    }

    @Test
    @DisplayName("forEachAll: без предохранителя — count не вызывается, стрим идёт даже на миллионах строк")
    void forEachAllHasNoCap() {
        WaybillPeriodScan scan = new WaybillPeriodScan(waybills, em, 1000);
        when(waybills.streamByPeriod(any(), any())).thenReturn(stubs(1500).stream());

        int[] seen = {0};
        scan.forEachAll(LocalDate.of(2020, 1, 1), LocalDate.of(2026, 12, 31), null, wb -> seen[0]++);

        assertThat(seen[0]).isEqualTo(1500);
        verify(waybills, never()).countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(any(), any());
        verify(em, times(3)).clear();
    }

    @Test
    @DisplayName("forEachCompleted: фильтр статуса COMPLETED в SQL (count и стрим по статусу), с предохранителем")
    void forEachCompletedFiltersInSql() {
        WaybillPeriodScan scan = new WaybillPeriodScan(waybills, em, 1000);
        when(waybills.countByStatusAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                eq(tj.mintrans.epd.waybill.domain.WaybillStatus.COMPLETED), any(), any())).thenReturn(4L);
        when(waybills.streamByPeriodAndStatus(
                eq(tj.mintrans.epd.waybill.domain.WaybillStatus.COMPLETED), any(), any())).thenReturn(stubs(4).stream());

        int[] seen = {0};
        scan.forEachCompleted(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), null, wb -> seen[0]++);

        assertThat(seen[0]).isEqualTo(4);
        verify(waybills, never()).streamByPeriod(any(), any());
    }

    @Test
    @DisplayName("перевёрнутый или пустой период — ничего не делает")
    void invalidPeriod() {
        WaybillPeriodScan scan = new WaybillPeriodScan(waybills, em, 100_000);
        int[] seen = {0};
        scan.forEach(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 1), null, wb -> seen[0]++);
        scan.forEach(null, LocalDate.of(2026, 9, 1), null, wb -> seen[0]++);
        assertThat(seen[0]).isZero();
        verify(waybills, never()).countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(any(), any());
    }

    @Test
    @DisplayName("count: все / область / пустая область")
    void counts() {
        WaybillPeriodScan scan = new WaybillPeriodScan(waybills, em, 100_000);
        OffsetDateTime lo = WaybillPeriodScan.lower(LocalDate.of(2026, 9, 1));
        OffsetDateTime hi = WaybillPeriodScan.upper(LocalDate.of(2026, 9, 30));
        when(waybills.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(lo, hi)).thenReturn(42L);
        when(waybills.countByOrganizationRmaInAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(Set.of("1"), lo, hi)).thenReturn(7L);

        assertThat(scan.count(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null)).isEqualTo(42L);
        assertThat(scan.count(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), Set.of("1"))).isEqualTo(7L);
        assertThat(scan.count(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), Set.of())).isZero();
    }
}
