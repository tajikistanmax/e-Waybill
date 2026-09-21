package tj.mintrans.epd.masterdata.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tj.mintrans.epd.masterdata.domain.Vehicle;
import tj.mintrans.epd.masterdata.repository.VehicleRepository;

import java.time.Year;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIGRATION.md §12.3 (год выпуска 1900…текущий+1, legacy {@code kvd/StoreTransportRequest}) и
 * §12.13 (номер стоянки уникален в организации, legacy {@code ParkingRequest}).
 */
class VehicleCardRulesTest {

    private final VehicleRepository vehicles = mock(VehicleRepository.class);
    private final VehicleCardRules rules = new VehicleCardRules(vehicles);

    @Test
    @DisplayName("год выпуска: пусто, 1900 и текущий+1 — допустимы; 1899 и текущий+2 — 422")
    void yearManufactureRange() {
        int now = Year.now().getValue();
        assertThatCode(() -> rules.assertYearManufacture(null)).doesNotThrowAnyException();
        assertThatCode(() -> rules.assertYearManufacture((short) 1900)).doesNotThrowAnyException();
        assertThatCode(() -> rules.assertYearManufacture((short) (now + 1))).doesNotThrowAnyException();

        assertThatThrownBy(() -> rules.assertYearManufacture((short) 1899))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThatThrownBy(() -> rules.assertYearManufacture((short) (now + 2)))
                .hasMessageContaining("Год выпуска: 1900–" + (now + 1));
    }

    @Test
    @DisplayName("номер стоянки: занят другим ТС организации — 409; свой же номер при обновлении и пустой — ок")
    void parkingNumberUniquePerOrganization() {
        UUID org = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        Vehicle other = mock(Vehicle.class);
        when(other.getId()).thenReturn(otherId);
        when(vehicles.findByOrganizationIdAndParkingNumber(org, "1203")).thenReturn(List.of(other));

        assertThatThrownBy(() -> rules.assertParkingNumberUnique(org, " 1203 ", new Vehicle()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        Vehicle same = mock(Vehicle.class);
        when(same.getId()).thenReturn(otherId);
        assertThatCode(() -> rules.assertParkingNumberUnique(org, "1203", same)).doesNotThrowAnyException();

        assertThatCode(() -> rules.assertParkingNumberUnique(org, "  ", new Vehicle())).doesNotThrowAnyException();
        assertThatCode(() -> rules.assertParkingNumberUnique(org, null, null)).doesNotThrowAnyException();
    }
}
