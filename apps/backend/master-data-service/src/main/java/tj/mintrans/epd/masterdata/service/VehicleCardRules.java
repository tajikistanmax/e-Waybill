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

    /** Вид ТС «легковой» (transport_type_id = 4 в legacy): госномер строго 3–4 цифры + 2 латинские буквы + 2 цифры. */
    public static final short TRANSPORT_TYPE_CAR = 4;
    private static final java.util.regex.Pattern CAR_PLATE = java.util.regex.Pattern.compile("^\\d{3,4}[A-Z]{2}\\d{2}$");

    /**
     * Формат госномера (MIGRATION.md 12.2, legacy {@code ParkingRequest::withValidator}): только для легковых
     * (тип 4) — {@code 234AB01} / {@code 1234AB01}; прочие виды ТС в legacy формат не проверяют. Номер уже
     * канонизирован (верхний регистр). Нарушение → 422 с текстом legacy.
     */
    public static void assertPlateFormat(Short transportType, String canonicalNumber) {
        if (transportType == null || transportType != TRANSPORT_TYPE_CAR || canonicalNumber == null) {
            return;
        }
        if (!CAR_PLATE.matcher(canonicalNumber).matches()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Госномер легкового ТС: 3 или 4 цифры, 2 латинские буквы и 2 цифры, например 234AB01 или 1234AB01 "
                            + "(Рақами автомобил бояд 3 ё 4 рақам, 2 ҳарфи калони англисӣ ва 2 рақам дошта бошад)");
        }
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
