package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.domain.WaybillType;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Состав накладной, который видит внешний кабинет (блок A8 сквозной приёмки 22.09.2026).
 *
 * <p>До правки кабинет грузоотправителя, экспедитора и таможни получал путевой лист целиком:
 * снимок организации с банковскими реквизитами и долей дохода, снимок ТС и снимок водителя с
 * паспортом, номером прав и сроком медицинской справки. Внешней стороне нужна сама накладная,
 * название предприятия и Ф.И.О. водителя — не более.</p>
 */
class ConsignmentViewTest {

    private static Waybill sample() {
        var wb = new Waybill();
        wb.setNumber("01-26-06-0000142-3");
        wb.setWaybillType(WaybillType.WB_TRUCK);
        wb.setStatus(WaybillStatus.COMPLETED);
        wb.setOrganizationRma("025680800");
        wb.setVehicleRegNumber("8800TJ11");
        wb.setDriverRma("555555555");
        wb.setOrganizationSnapshot(Map.of("name", "Автобусный парк", "bank", "Ориёнбонк",
                "percentIncome", 12.5, "carrierLicenseNumber", "АЛ-934013"));
        wb.setDriverSnapshot(Map.of("fullName", "Каримов А.", "passport", "A1234567",
                "licenseNumber", "CC7654321", "medCertValidTo", "2027-03-02"));
        wb.setVehicleSnapshot(Map.of("registrationNumber", "8800TJ11", "vincode", "XYZ123"));
        wb.setTypeData(Map.of("senderName", "Отправитель", "cargoName", "Цемент"));
        return wb;
    }

    @Test
    @DisplayName("наружу уходят только поля накладной, название предприятия и Ф.И.О. водителя")
    void viewExposesOnlyConsignmentFields() {
        List<String> fields = Arrays.stream(ConsignmentCabinetService.ConsignmentView.class.getRecordComponents())
                .map(RecordComponent::getName).toList();

        assertThat(fields).containsExactlyInAnyOrder(
                "id", "number", "waybillType", "status", "validFrom", "validTo",
                "organizationRma", "organizationName", "vehicleRegNumber", "driverName",
                "typeData", "createdAt", "updatedAt");
        assertThat(fields).doesNotContain("organizationSnapshot", "vehicleSnapshot", "driverSnapshot",
                "driverRma", "dispatcherRma", "odometerExit", "odometerEntry");
    }

    @Test
    @DisplayName("из снимков берутся только название предприятия и Ф.И.О. водителя")
    void viewTakesOnlyNamesFromSnapshots() {
        var view = ConsignmentCabinetService.ConsignmentView.of(sample());

        assertThat(view.organizationName()).isEqualTo("Автобусный парк");
        assertThat(view.driverName()).isEqualTo("Каримов А.");
        assertThat(view.number()).isEqualTo("01-26-06-0000142-3");
        assertThat(view.waybillType()).isEqualTo("WB_TRUCK");
        assertThat(view.status()).isEqualTo("COMPLETED");
        assertThat(view.typeData()).containsEntry("cargoName", "Цемент");
    }

    @Test
    @DisplayName("пустые снимки не роняют преобразование")
    void missingSnapshotsAreSafe() {
        var wb = new Waybill();
        wb.setWaybillType(WaybillType.WB_TRUCK_INTL);
        wb.setStatus(WaybillStatus.DRAFT);

        var view = ConsignmentCabinetService.ConsignmentView.of(wb);

        assertThat(view.organizationName()).isNull();
        assertThat(view.driverName()).isNull();
        assertThat(view.waybillType()).isEqualTo("WB_TRUCK_INTL");
    }
}
