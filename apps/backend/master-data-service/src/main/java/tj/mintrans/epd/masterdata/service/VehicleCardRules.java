package tj.mintrans.epd.masterdata.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tj.mintrans.epd.masterdata.domain.Vehicle;
import tj.mintrans.epd.masterdata.repository.VehicleRepository;

import java.time.Year;
import java.util.UUID;

/**
 * Правила карточки ТС из legacy-валидаций (MIGRATION.md §12):
 * <ul>
 *   <li>12.3 — год выпуска: 4 цифры, 1900 … текущий год + 1 ({@code kvd/StoreTransportRequest:
 *       year_manufacture … min:1900|max:date('Y')+1});</li>
 *   <li>12.13 — номер стоянки уникален в пределах организации ({@code ParkingRequest: Rule::unique('parkings')
 *       ->where(number, company_id)}), при обновлении — без учёта самого ТС.</li>
 * </ul>
 * Нарушение → 422 (год) / 409 (номер стоянки занят другим ТС организации).
 */
@Component
public class VehicleCardRules {

    private final VehicleRepository vehicles;

    public VehicleCardRules(VehicleRepository vehicles) {
        this.vehicles = vehicles;
    }

    /** Год выпуска: пусто — допустимо; иначе 1900 … текущий год + 1. */
    public void assertYearManufacture(Short year) {
        if (year == null) {
            return;
        }
        int max = Year.now().getValue() + 1;
        if (year < 1900 || year > max) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Год выпуска: 1900–%d".formatted(max));
        }
    }

    /** Номер стоянки (если задан) не должен принадлежать другому ТС той же организации. */
    public void assertParkingNumberUnique(UUID organizationId, String parkingNumber, Vehicle current) {
        if (parkingNumber == null || parkingNumber.isBlank() || organizationId == null) {
            return;
        }
        boolean takenByOther = vehicles.findByOrganizationIdAndParkingNumber(organizationId, parkingNumber.trim())
                .stream()
                .anyMatch(v -> current == null || current.getId() == null || !v.getId().equals(current.getId()));
        if (takenByOther) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Номер стоянки %s уже занят другим ТС этой организации".formatted(parkingNumber.trim()));
        }
    }
}
