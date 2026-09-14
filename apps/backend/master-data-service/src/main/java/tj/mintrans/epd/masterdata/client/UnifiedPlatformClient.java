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
            LocalDate licenseTo,
            // № лицензии перевозчика — из платформы лицензирования (обязателен для печати 5Б-БМ).
            String carrierLicenseNumber,
            // Дата рождения физлица/ИП — из налоговой. Опционально (nullable): у юрлица её нет,
            // да и платформа может её не отдать. Нужна для проверки несовершеннолетия водителя (ТЗ §6.3).
            LocalDate birthDate) {
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
            LocalDate controlCardValidTo,
            // № контрольной карточки (не только срок) — печатается на бланке как «Иҷозатнома».
            String controlCardNumber,
            // № сертификата для международных перевозок (бланк 5Б-БМ/CMR).
            String intlCertificateNumber,
            // Полис ОСАГО/КАСКО ТС — WaybillService.checkPreflight блокирует выдачу при истечении.
            LocalDate insuranceValidTo,
            // Допуск ТС к перевозке опасных грузов (ADR) — WaybillService.checkPreflight,
            // чек №19 checks.yaml («Опасные грузы: свидетельства о допуске ТС и водителя»).
            LocalDate adrApprovalValidTo) {
    }

    record DriverLicense(
            String licenseNumber,
            String categories,
            LocalDate validTo,
            String medCertNumber,   // медсправка (реестр Минздрава через единую платформу)
            LocalDate medCertValidTo,
            // № талона 20-часового курса БДД — печатается на бланках 1-А/1-АД/3-С.
            String safetyCourseNumber,
            LocalDate safetyCourseValidTo,
            // Допуск водителя к перевозке опасных грузов (ADR) — WaybillService.checkPreflight,
            // чек №19 checks.yaml.
            LocalDate adrCertValidTo,
            // Общий стаж вождения, лет — из ГАИ. Опционально (nullable): платформа может не отдать.
            // Поле правила «не менее N лет» для перевозки детей.
            Short experienceYears) {
    }

    record PermitInfo(
            String permitNumber,
            boolean valid,
            LocalDate validTo,
            String country) {       // страна, для которой выдан дозвол
    }
}
