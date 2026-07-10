package tj.mintrans.epd.masterdata.client;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Клиент единой платформы транспорта Минтранса РТ.
 *
 * Принцип экосистемы: субъекты (физлицо / ИП / юрлицо) и объекты (транспортные
 * средства) регистрируются ОДИН РАЗ в единой платформе. Данные субъекта приходят
 * из налоговой по ИНН, данные ТС — из базы ГАИ по госномеру, водительское
 * удостоверение — из ГАИ. Платформы экосистемы (лицензирование, сертификаты, ЭПД)
 * субъектов и объектов НЕ регистрируют — только запрашивают по API.
 *
 * Реализации: {@link StubUnifiedPlatformClient} (dev, имитация) и
 * {@link HttpUnifiedPlatformClient} (прод, UNIFIED_PLATFORM_MODE=http).
 */
public interface UnifiedPlatformClient {

    /** Субъект по ИНН (источник — налоговая через единую платформу). */
    Optional<Subject> findSubject(String inn);

    /** Транспортное средство по госномеру (источник — база ГАИ через единую платформу). */
    Optional<VehicleInfo> findVehicle(String registrationNumber);

    /** Водительское удостоверение физлица по ИНН (источник — ГАИ). */
    Optional<DriverLicense> findDriverLicense(String inn);

    /** Дозвол на международную перевозку (источник — система E-PERMIT). */
    Optional<PermitInfo> findPermit(String permitNumber);

    /** Субъект единой платформы: физлицо, ИП или юрлицо. */
    record Subject(
            String inn,
            String subjectType,     // PHYSICAL | IP | LEGAL
            String name,            // ФИО физлица/ИП или наименование юрлица
            Short regionId,
            String cityName,
            String address,
            String phone,
            String email,
            String headName,        // руководитель (для юрлица)
            LocalDate licenseFrom,  // лицензия перевозчика — из платформы лицензирования
            LocalDate licenseTo) {
    }

    /** Объект единой платформы: транспортное средство из базы ГАИ. */
    record VehicleInfo(
            String registrationNumber,
            Short transportType,    // 1..6 (справочник ЭПД)
            String brand,
            String vincode,
            Short yearManufacture,
            Integer capacity,
            BigDecimal carrying,
            LocalDate techInspectionValidTo,
            LocalDate controlCardValidTo) {
    }

    record DriverLicense(
            String licenseNumber,
            String categories,
            LocalDate validTo,
            String medCertNumber,   // медсправка (реестр Минздрава через единую платформу)
            LocalDate medCertValidTo) {
    }

    record PermitInfo(
            String permitNumber,
            boolean valid,
            LocalDate validTo,
            String country) {       // страна, для которой выдан дозвол
    }
}
