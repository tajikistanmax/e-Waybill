package tj.mintrans.epd.masterdata.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;
import tj.mintrans.epd.masterdata.domain.Organization;
import tj.mintrans.epd.masterdata.repository.DriverRepository;
import tj.mintrans.epd.masterdata.repository.EmployeeRepository;
import tj.mintrans.epd.masterdata.repository.OrganizationRepository;
import tj.mintrans.epd.masterdata.repository.VehicleRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Сверка 25.09, G1 — выгрузки legacy {@code ref/*}: updated_after, страница 1..N по 100, {data, meta}. */
class RefExportControllerTest {

    @Test
    @DisplayName("updated_after: dd.MM.yyyy и ISO; пусто или мусор — 400; page с 1, по 100")
    void params() {
        assertThat(RefExportController.since("01.09.2026").toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(RefExportController.since("2026-09-01").toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThatThrownBy(() -> RefExportController.since(null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> RefExportController.since("вчера")).isInstanceOf(ResponseStatusException.class);
        assertThat(RefExportController.pageable("3").getPageNumber()).isEqualTo(2);
        assertThat(RefExportController.pageable("3").getPageSize()).isEqualTo(100);
        assertThatThrownBy(() -> RefExportController.pageable("x")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("companies: data в snake_case и meta как у Laravel paginate")
    @SuppressWarnings("unchecked")
    void companiesEnvelope() {
        OrganizationRepository orgs = mock(OrganizationRepository.class);
        Organization o = new Organization();
        o.setName("КВД Автобуси Душанбе");
        o.setRma("025680800");
        when(orgs.findByUpdatedAtAfter(any(), any())).thenReturn(new PageImpl<>(List.of(o), PageRequest.of(0, 100), 1));
        var c = new RefExportController(orgs, mock(EmployeeRepository.class), mock(DriverRepository.class),
                mock(VehicleRepository.class));

        Map<String, Object> r = c.companies("1", "01.09.2026");
        List<Map<String, Object>> data = (List<Map<String, Object>>) r.get("data");
        assertThat(data.getFirst()).containsEntry("rma", "025680800").containsKey("name_head");
        assertThat((Map<String, Object>) r.get("meta")).containsEntry("current_page", 1).containsEntry("last_page", 1)
                .containsEntry("per_page", 100).containsEntry("total", 1L);
    }
}
