package tj.mintrans.epd.masterdata.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.masterdata.domain.Driver;
import tj.mintrans.epd.masterdata.repository.DriverRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIGRATION.md §11.7 — авто-присвоение табельного номера водителя (legacy {@code DriverObserver}:
 * {@code number = max(number по компании) + 1} при создании/обновлении без номера).
 */
class DriverTabNumbersTest {

    private final DriverRepository drivers = mock(DriverRepository.class);
    private final DriverTabNumbers numbers = new DriverTabNumbers(drivers);
    private final UUID org = UUID.randomUUID();

    @Test
    @DisplayName("новый водитель без номера: max по организации + 1 (нечисловые и пустые номера не учитываются)")
    void nextIsMaxPlusOne() {
        when(drivers.findTabNumbersByOrganization(org)).thenReturn(List.of("7", " 12 ", "A-3", "", "9"));

        assertThat(numbers.resolve(null, Optional.empty(), org)).isEqualTo("13");
        assertThat(numbers.resolve("   ", Optional.empty(), org)).isEqualTo("13");
    }

    @Test
    @DisplayName("первый водитель организации получает номер 1")
    void firstDriverGetsOne() {
        when(drivers.findTabNumbersByOrganization(org)).thenReturn(List.of());

        assertThat(numbers.next(org)).isEqualTo("1");
    }

    @Test
    @DisplayName("явно заданный номер сохраняется как есть (legacy при создании перезаписывал — не переносим)")
    void explicitNumberKept() {
        assertThat(numbers.resolve(" 42 ", Optional.empty(), org)).isEqualTo("42");
        verify(drivers, never()).findTabNumbersByOrganization(org);
    }

    @Test
    @DisplayName("обновление без номера: прежний номер водителя сохраняется; без прежнего — выдаётся новый")
    void updateKeepsExistingNumber() {
        Driver withNumber = new Driver();
        withNumber.setTabNumber("5");
        assertThat(numbers.resolve(null, Optional.of(withNumber), org)).isEqualTo("5");
        verify(drivers, never()).findTabNumbersByOrganization(org);

        Driver without = new Driver();
        when(drivers.findTabNumbersByOrganization(org)).thenReturn(List.of("5", "8"));
        assertThat(numbers.resolve(null, Optional.of(without), org)).isEqualTo("9");
    }
}
