package tj.mintrans.epd.waybill.print;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.client.MasterDataClient;
import tj.mintrans.epd.waybill.domain.Expense;
import tj.mintrans.epd.waybill.domain.FuelRecord;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillTitle;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.domain.WorkDay;
import tj.mintrans.epd.waybill.repository.ExpenseRepository;
import tj.mintrans.epd.waybill.repository.FuelRecordRepository;
import tj.mintrans.epd.waybill.repository.WaybillTitleRepository;
import tj.mintrans.epd.waybill.repository.WorkDayRepository;
import tj.mintrans.epd.waybill.service.QrTokenService;
import tj.mintrans.epd.waybill.service.WaybillService;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Печатный бланк путевого листа в PDF (перенос {@code tj.etrans.rohkhat.print.WaybillPrintService}).
 *
 * <p>Для каждого вида ПЛ — свой шаблон утверждённой формы (Замима 7 к Қоидаи нақлиёти
 * автомобилӣ): 1-А (микроавтобус), Т(1-АД) (автобус/троллейбус), 2-Б (грузовой),
 * 3-С (такси/легковой), 5Б-БМ (международный грузовой), 4М-БМ (международный
 * пассажирский). Прочие виды — универсальный бланк.</p>
 */
@Service
public class WaybillPrintService {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TM = DateTimeFormatter.ofPattern("HH:mm");

    private static final String[] MONTHS_TJ = {
            "январ", "феврал", "март", "апрел", "май", "июн",
            "июл", "август", "сентябр", "октябр", "ноябр", "декабр"};
    private static final Map<Integer, String> FUEL_NAMES = Map.of(
            1, "Бензин", 2, "Дизел", 3, "Гази моеъшуда", 4, "Гази табиӣ", 5, "Барқ (электро)");

    /** Виды расходов рейса (§12, ExpenseService.TYPES) — человекочитаемые метки (tg / ru). */
    private static final Map<String, String> EXPENSE_TYPE_NAMES = Map.of(
            "PER_DIEM", "Хароҷоти рӯзона (суточные)",
            "TOLL", "Роҳи пулакӣ (платная дорога)",
            "PARKING", "Таваққуфгоҳ (парковка)",
            "LODGING", "Ҷойгиршавӣ (проживание)",
            "REPAIR", "Таъмир (ремонт)",
            "OTHER", "Дигар (прочее)");

    /** Ходуди фаъолият (7 именованных зон, spec/data/dictionaries.yaml: regions). */
    private static final Map<Integer, String> REGION_NAMES = Map.of(
            1, "Душанбе", 2, "ВМКБ", 3, "Суғд", 4, "Рашт", 5, "Хатлон-Бохтар", 6, "Хатлон-Кӯлоб", 7, "Ҳисор");

    private static final Map<String, String> TITLE_LABEL = Map.of(
            "T1", "Выпуск (Т1)", "T2", "Предрейсовый медосмотр (Т2)", "T3", "Предрейсовый техконтроль (Т3)",
            "T4", "Выезд на линию (Т4)", "T5", "Возвращение (Т5)", "T6", "Послерейсовый медосмотр (Т6)",
            "CORRECTION", "Корректировка");
    private static final Map<String, String> ROLE_LABEL = Map.of(
            "DISPATCHER", "Диспетчер", "DOCTOR", "Медработник", "MECHANIC", "Механик");

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WaybillPrintService.class);

    private final WaybillService waybills;
    private final WaybillTitleRepository titles;
    private final WorkDayRepository workDays;
    private final FuelRecordRepository fuelRecords;
    private final ExpenseRepository expenses;
    private final tj.mintrans.epd.waybill.calc.WaybillCalcAssembler calcAssembler;
    private final QrTokenService qrToken;
    private final QrImageService qrImage;
    private final PdfRenderService pdf;
    private final MasterDataClient masterData;
    private final String publicBaseUrl;

    public WaybillPrintService(WaybillService waybills, WaybillTitleRepository titles,
                               WorkDayRepository workDays, FuelRecordRepository fuelRecords,
                               ExpenseRepository expenses,
                               tj.mintrans.epd.waybill.calc.WaybillCalcAssembler calcAssembler,
                               QrTokenService qrToken, QrImageService qrImage, PdfRenderService pdf,
                               MasterDataClient masterData,
                               @Value("${epd.public-base-url:http://localhost:3000}") String publicBaseUrl) {
        this.waybills = waybills;
        this.titles = titles;
        this.workDays = workDays;
        this.fuelRecords = fuelRecords;
        this.expenses = expenses;
        this.calcAssembler = calcAssembler;
        this.qrToken = qrToken;
        this.qrImage = qrImage;
        this.pdf = pdf;
        this.masterData = masterData;
        this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
    }

    /**
     * PDF-бланк путевого листа по id (область видимости — как у {@code WaybillService.get}).
     * Регистрирует печать (отметка «Копия» со 2-го раза, QA §17) в отдельной REQUIRES_NEW
     * транзакции {@code WaybillService.registerPrint} — она выполняется и коммитится до
     * начала readOnly-транзакции этого метода, поэтому счётчик не зависит от исхода рендера.
     */
    @Transactional(readOnly = true)
    public byte[] renderPdf(java.util.UUID id) {
        boolean isCopy = waybills.registerPrint(id);
        Waybill wb = waybills.get(id);
        Map<String, Object> m = model(wb);
        m.put("isCopy", isCopy);
        return pdf.render(templateFor(wb.getWaybillType()), m);
    }

    public String fileName(java.util.UUID id) {
        return PdfRenderService.fileName(waybills.get(id).getNumber());
    }

    /**
     * Рендер БЕЗ регистрации печати (счётчик «Копия» не трогается) — проверка и предпросмотр редактируемых
     * шаблонов (MIGRATION.md 7.1). {@code template} — имя шаблона ({@code print/…}); null — бланк вида ПЛ.
     */
    @Transactional(readOnly = true)
    public byte[] renderPreview(java.util.UUID id, String template) {
        Waybill wb = waybills.get(id);
        Map<String, Object> m = model(wb);
        m.put("isCopy", false);
        return pdf.render(template == null ? templateFor(wb.getWaybillType()) : template, m);
    }

    /**
     * Накладная (приложение к 2-Б, борхат): виды 1 (корбай/сдельно) и 2 (соатбай/повременно)
     * — тот же шаблон, различие полей (экспедитор) задаётся флагом {@code shipmentKind}
     * в модели. Только для WB_TRUCK/WB_DANGEROUS — 2-Б хранит эту форму рейса.
     */
    @Transactional(readOnly = true)
    public byte[] renderAttachmentPdf(java.util.UUID id) {
        Waybill wb = waybills.get(id);
        requireType(wb, "накладная (приложение к 2-Б)", WaybillType.WB_TRUCK, WaybillType.WB_DANGEROUS);
        return pdf.render("print/waybill2b-attachment", model(wb));
    }

    /** CMR — международная товарно-транспортная накладная к 5Б-БМ (Конвенция КДПГ). */
    @Transactional(readOnly = true)
    public byte[] renderCmrPdf(java.util.UUID id) {
        Waybill wb = waybills.get(id);
        requireType(wb, "CMR", WaybillType.WB_TRUCK_INTL);
        return pdf.render("print/cmr", model(wb));
    }

    /**
     * Накладная/СМР для внешнего кабинета (грузоотправитель, экспедитор, таможня — MIGRATION.md 1.1/3.11):
     * лист передаётся сущностью (доступ уже проверен вызывающим по клиентам пользователя), без тенант-скоупа.
     */
    public byte[] renderConsignmentPdf(Waybill wb) {
        if (wb.getWaybillType() == WaybillType.WB_TRUCK_INTL) {
            return pdf.render("print/cmr", model(wb));
        }
        requireType(wb, "накладная (приложение к 2-Б)", WaybillType.WB_TRUCK, WaybillType.WB_DANGEROUS);
        return pdf.render("print/waybill2b-attachment", model(wb));
    }

    public String attachmentFileName(java.util.UUID id) {
        return "attachment-" + PdfRenderService.fileName(waybills.get(id).getNumber());
    }

    public String cmrFileName(java.util.UUID id) {
        return "cmr-" + PdfRenderService.fileName(waybills.get(id).getNumber());
    }

    private static void requireType(Waybill wb, String docName, WaybillType... allowed) {
        for (WaybillType t : allowed) {
            if (t == wb.getWaybillType()) {
                return;
            }
        }
        throw new tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException(
                "Документ «" + docName + "» не предусмотрен для типа ПЛ " + wb.getWaybillType());
    }

    /** Шаблон бланка по виду ПЛ. */
    static String templateFor(WaybillType type) {
        return switch (type) {
            case WB_MINIBUS -> "print/waybill1a";
            case WB_BUS, WB_TROLLEYBUS -> "print/waybill1ad";
            case WB_PAX_INTL -> "print/waybill4mbm";
            case WB_TRUCK, WB_DANGEROUS -> "print/waybill2b";
            case WB_TRUCK_INTL -> "print/waybill5bbm";
            case WB_TAXI, WB_CAR -> "print/waybill3c";
            default -> "print/waybill";
        };
    }

    // ------------------------------------------------------------------

    private Map<String, Object> model(Waybill wb) {
        Map<String, Object> org = wb.getOrganizationSnapshot() == null ? Map.of() : wb.getOrganizationSnapshot();
        Map<String, Object> veh = wb.getVehicleSnapshot() == null ? Map.of() : wb.getVehicleSnapshot();
        Map<String, Object> drv = wb.getDriverSnapshot() == null ? Map.of() : wb.getDriverSnapshot();
        Map<String, Object> td = wb.getTypeData() == null ? Map.of() : wb.getTypeData();

        List<WorkDay> days = workDays.findByWaybillIdOrderByWorkDate(wb.getId());
        List<FuelRecord> fuel = fuelRecords.findByWaybillIdOrderByCreatedAt(wb.getId());

        // Единый расчёт: коэффициент нормирования топлива (K, зарбкунанда) и норматив
        // расхода по каждому виду топлива. Расчёт может обращаться к master-data
        // и падать — бланк обязан печататься в любом случае.
        String calcK = "—";
        String calcMultiplier = "—";
        Map<Long, Double> normByFuel = new java.util.HashMap<>();
        try {
            var view = calcAssembler.calculate(wb, tj.mintrans.epd.waybill.calc.WaybillCalcAssembler.Supplement.empty());
            var coef = view.passenger() != null ? view.passenger().coefficients()
                    : view.cargo() != null ? view.cargo().coefficients() : null;
            if (coef != null) {
                calcK = String.valueOf(coef.k());
                calcMultiplier = String.format(java.util.Locale.US, "%.2f", coef.multiplier());
            }
            var fuelCalc = view.passenger() != null ? view.passenger().fuels()
                    : view.cargo() != null ? view.cargo().fuels() : null;
            if (fuelCalc != null) {
                fuelCalc.forEach(fc -> normByFuel.merge(fc.fuelId(), fc.normLiters(), Double::sum));
            }
        } catch (RuntimeException e) {
            log.debug("Печать {}: расчёт недоступен ({})", wb.getId(), e.toString());
        }

        Map<String, Object> m = new LinkedHashMap<>();
        WaybillType type = wb.getWaybillType();

        // --- Заголовок и юр. атрибуты
        m.put("number", wb.getNumber() != null ? wb.getNumber() : "(рақам таъин нашудааст)");
        m.put("branchSerial", wb.getBranchSerial() != null
                ? wb.getBranchSerial() + "/" + (wb.getBranchSerialYear() != null ? wb.getBranchSerialYear() : "")
                : "—");
        m.put("formName", type.legacyForm());
        m.put("formTitle", "Шакли E-варақаи роҳхат (" + type.legacyForm() + ")");
        m.put("companyName", str(org.get("name")));
        m.put("companyAddress", firstNonBlank(str(org.get("address")), str(org.get("cityName"))));
        m.put("ownershipName", subjectTypeName(str(org.get("subjectType"))));
        m.put("orgRegion", str(org.get("regionId")));

        OffsetDateTime from = wb.getValidFrom() != null ? wb.getValidFrom() : wb.getCreatedAt();
        OffsetDateTime to = wb.getValidTo() != null ? wb.getValidTo() : from;
        if (from != null) {
            m.put("day", from.getDayOfMonth());
            m.put("monthName", MONTHS_TJ[from.getMonthValue() - 1]);
            m.put("year", from.getYear());
            m.put("fromDay", from.getDayOfMonth());
        }
        if (to != null) {
            m.put("toDay", to.getDayOfMonth());
            m.put("toMonthName", MONTHS_TJ[to.getMonthValue() - 1]);
            m.put("toYear", to.getYear());
            m.put("dayTo", to.getDayOfMonth());
        }
        m.put("validFrom", from != null ? DT.format(from) : "—");
        m.put("validTo", to != null ? DT.format(to) : "—");

        // --- ТС и водитель
        m.put("registrationNumber", wb.getVehicleRegNumber());
        m.put("brandName", str(veh.get("brand")));
        m.put("garageNumber", orDash(str(veh.get("parkingNumber"))));
        m.put("parkingNumber", orDash(str(veh.get("parkingNumber"))));
        m.put("vehicle", (str(veh.get("brand")) + " · " + wb.getVehicleRegNumber()).trim());
        m.put("driverName", firstNonBlank(str(drv.get("fullName")), wb.getDriverRma()));
        m.put("driverNumber", firstNonBlank(str(drv.get("tabNumber")), wb.getDriverRma()));
        m.put("driverLicense", orDash(str(drv.get("licenseNumber")))
                + (drv.get("licenseCategories") != null ? " (" + drv.get("licenseCategories") + ")" : ""));
        // «Иҷозатнома (варақаи назоратӣ)» бланка — это № контрольного листа ТС, не медсправка водителя.
        m.put("checklistNumber", orDash(str(veh.get("controlCardNumber"))));
        // «Талони курси 20 соатаи ҚҲР» — номер талона водителя, не дата окончания курса.
        m.put("lessons20Hours", orDash(str(drv.get("safetyCourseNumber"))));
        m.put("driverLessons20Hours", orDash(str(drv.get("safetyCourseNumber"))));
        // № лицензии перевозчика и № сертификата ТС для международных перевозок (бланк 5Б-БМ).
        m.put("carrierLicenseNumber", orDash(str(org.get("carrierLicenseNumber"))));
        m.put("certificateNumber", orDash(str(veh.get("intlCertificateNumber"))));
        Map<String, Object> sd = mapOrEmpty(td.get("secondDriverSnapshot"));
        m.put("secondDriver", firstNonBlank(str(sd.get("fullName")), wb.getSecondDriverRma(), "—"));
        m.put("secondDriverName", firstNonBlank(str(sd.get("fullName")), wb.getSecondDriverRma(), "—"));
        m.put("secondDriverLicense", orDash(str(sd.get("licenseNumber"))));
        m.put("secondDriverNumber", firstNonBlank(str(sd.get("tabNumber")), wb.getSecondDriverRma(), "—"));
        m.put("conductorName", orDash(str(td.get("conductorName"))));
        m.put("conductorNumber", orDash(str(td.get("conductorTab"))));
        // «Колонна» / «Бригада» бланка Т(1-АД) — реальные диспетчерские графы, не мёртвый код.
        m.put("columnNumber", orDash(str(td.get("columnNumber"))));
        m.put("brigadeNumber", orDash(str(td.get("brigadeNumber"))));

        // --- Маршрут / задание
        m.put("routeNumber", orDash(wb.getRoute()));
        m.put("routeName", orDash(wb.getRoute()));
        m.put("schedule", orDash(wb.getSchedule()));
        // «Самт» бланка 2-Б — для WB_TRUCK берём РЕАЛЬНОЕ направление из справочника Direction
        // (typeData.directionId), а не пару стран погрузки/разгрузки — те заполняются только
        // у международных ПЛ (WB_TRUCK_INTL/5Б-БМ) и на внутреннем 2-Б почти всегда пусты.
        m.put("directionTitle", type == WaybillType.WB_TRUCK
                ? resolveDirectionTitle(td.get("directionId"))
                : orDash(str(td.get("loadCountry"))) + " → " + orDash(str(td.get("unloadCountry"))));
        // «Заказчик» бланка 2-Б — снимок имени из справочника Client (typeData.clientId/clientName),
        // отдельно от clientName-семантики других бланков (там clientName — маршрут/супоришдиҳанда).
        m.put("customerName", orDash(str(td.get("clientName"))));
        // «Ходуди фаъолият» (2-Б/3-С) — коды зон 1–7 → человекочитаемые названия (comma-joined).
        m.put("workRegions", workRegionsLabel(td.get("workRegions")));
        // «Хатсайр/супоришдиҳанда» бланка 3-С: имя груза (если оно вдруг проставлено на
        // пассажирском ПЛ) важнее вида услуги, а вид услуги — человекочитаемой меткой,
        // не сырым кодом typeData.serviceKind (было: "TAXI" на печать вместо перевода).
        m.put("clientName", firstNonBlank(str(td.get("cargoName")), serviceKindLabel(str(td.get("serviceKind"))), "—"));
        // Грузоотправитель / заказчик рейса — реестр контрагентов ещё не заведён (Тир 3 аудита бланков).
        m.put("consignorName", orDash(str(td.get("consignorName"))));
        m.put("cashierName", "—");

        // --- Операции (выезд/возврат/пробег)
        m.put("exitDate", from != null ? D.format(from) : "");
        m.put("exitTime", from != null ? TM.format(from) : "");
        m.put("entryDate", to != null ? D.format(to) : "");
        m.put("entryTime", to != null ? TM.format(to) : "");
        m.put("counterExit", orDash(str(wb.getOdometerExit())));
        m.put("counterEntry", orDash(str(wb.getOdometerEntry())));
        int distanceKm = wb.getOdometerExit() != null && wb.getOdometerEntry() != null
                ? Math.max(0, wb.getOdometerEntry() - wb.getOdometerExit()) : 0;
        m.put("distanceKm", distanceKm);
        m.put("cargoDistance", distanceKm);
        m.put("specialDeviceTime", orDash(str(td.get("specialWorkHours"))));
        m.put("conditionerTime", orDash(str(td.get("conditionerHours"))));
        m.put("specialMark", orDash(wb.getSpecialMark()));

        // --- Груз / прицепы / виза
        Map<String, Object> cargo = mapOrEmpty(td.get("cargo"));
        m.put("cargoName", firstNonBlank(str(cargo.get("name")), str(td.get("cargoName")), "—"));
        // Рамзи бор — сквозной номер груза (legacy cargos.number, «Рамз» в борхате прил. 1/2), 2.25.
        m.put("cargoNumber", cargoNumberOf(cargo, td));
        m.put("cargoUnit", orDash(str(cargo.get("unit"))));
        // Масса перевозимого груза; если не задана — грузоподъёмность ТС (как в старой системе).
        m.put("cargoWeight", firstNonBlank(str(cargo.get("weight")), str(veh.get("carrying")), "—"));
        m.put("cargoPackages", orDash(str(cargo.get("packages"))));
        m.put("cargoClass", orDash(str(cargo.get("class"))));
        m.put("adrClass", orDash(str(td.get("adrClass"))));
        m.put("cargoCapacity", orDash(str(veh.get("carrying"))));
        m.put("loadCountry", orDash(str(td.get("loadCountry"))));
        m.put("unloadCountry", orDash(str(td.get("unloadCountry"))));
        m.put("loadCity", orDash(str(td.get("loadCity"))));
        m.put("unloadCity", orDash(str(td.get("unloadCity"))));
        m.put("visaCountry", orDash(str(td.get("visaCountry"))));
        m.put("visaValidTo", orDash(str(td.get("visaValidTo"))));
        m.put("hasVisa", td.get("visaCountry") != null && !str(td.get("visaCountry")).isBlank());
        m.put("bbaNumber", orDash(str(td.get("bbaNumber"))));
        // Время прибытия в пункт призначения (5Б-БМ) — legacy arrival_time, отдельно от
        // времени возврата в парк (odometerEntry/entryTime): международный рейс может
        // прибыть к получателю задолго до формального закрытия ПЛ на базе.
        m.put("arrivalTime", orDash(str(td.get("arrivalTime"))));
        // «Шумораи мусофирон» 4-МБМ — перевезено пассажиров по факту рейса (legacy number_passengers, 3.14).
        m.put("passengersCount", orDash(str(td.get("passengersCount"))));
        m.put("permitNumber", orDash(str(td.get("permitNumber"))));
        m.put("transitCountries", td.get("transitCountries") instanceof List<?> tc
                ? tc.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("—") : "—");
        m.put("trailer1", trailerAt(td, 0));
        m.put("trailer2", trailerAt(td, 1));

        // --- Накладная (приложение к 2-Б / CMR к 5Б-БМ): стороны, груз, операции
        // погрузки-разгрузки. Перенос bill2b_attachment{1,2}.blade.php и
        // bill5bm_cargo.blade.php (Тир 3 аудита бланков — было полностью не перенесено).
        m.put("attachmentNumber", firstNonBlank(str(td.get("attachmentNumber")), wb.getNumber(), "—"));
        m.put("shipmentKind", str(td.get("shipmentKind")));
        m.put("senderName", orDash(firstNonBlank(str(td.get("senderName")), str(td.get("consignorName")))));
        m.put("senderAddress", orDash(str(td.get("senderAddress"))));
        m.put("receiverName", orDash(str(td.get("receiverName"))));
        m.put("receiverAddress", orDash(str(td.get("receiverAddress"))));
        m.put("forwarderName", orDash(str(td.get("forwarderName"))));
        m.put("cargoVolume", orDash(str(td.get("cargoVolume"))));
        m.put("cargoStatCode", orDash(str(td.get("cargoStatCode"))));
        m.put("submittedDocuments", orDash(str(td.get("submittedDocuments"))));
        m.put("customsOfficerName", orDash(str(td.get("customsOfficerName"))));
        m.put("customsConfirmedAt", orDash(str(td.get("customsConfirmedAt"))));

        List<Map<String, Object>> cargoOps = new ArrayList<>();
        Object rawOps = td.get("cargoOperations");
        if (rawOps instanceof List<?> ops && !ops.isEmpty()) {
            for (Object o : ops) {
                Map<String, Object> op = mapOrEmpty(o);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("operation", orDash(str(op.get("operation"))));
                row.put("executorName", orDash(str(op.get("executorName"))));
                row.put("method", orDash(str(op.get("method"))));
                row.put("arrival", orDash(str(op.get("arrival"))));
                row.put("departure", orDash(str(op.get("departure"))));
                row.put("downtimeMinutes", orDash(str(op.get("downtimeMinutes"))));
                row.put("additionalOps", orDash(str(op.get("additionalOps"))));
                row.put("signature", orDash(str(op.get("signature"))));
                cargoOps.add(row);
            }
        } else {
            // По умолчанию — 2 строки, как в оригинале: погрузка у отправителя, разгрузка у получателя.
            cargoOps.add(cargoOpRow("боркунӣ", str(m.get("senderName"))));
            cargoOps.add(cargoOpRow("борфарорӣ", str(m.get("receiverName"))));
        }
        m.put("cargoOperations", cargoOps);

        // --- Топливо
        List<Map<String, Object>> fuels = new ArrayList<>();
        for (FuelRecord fr : fuel) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", FUEL_NAMES.getOrDefault((int) fr.getFuelType(), "Топливо " + fr.getFuelType()));
            r.put("code", (int) fr.getFuelType());
            r.put("given", num(fr.getFuelGiven()));
            r.put("remainBeforeExit", num(fr.getRemainBeforeExit()));
            r.put("remainEntry", num(fr.getRemainEntry()));
            Double normLiters = normByFuel.get((long) fr.getFuelType());
            r.put("norm", normLiters == null ? "" : trimNum(normLiters));
            r.put("returned", num(fr.getReturned()));
            r.put("additional", num(fr.getAdditionalGiven()));
            fuels.add(r);
        }
        m.put("fuels", fuels);
        if (!fuels.isEmpty()) {
            m.put("firstFuelGiven", fuels.get(0).get("given"));
            m.put("firstFuelRemainBeforeExit", fuels.get(0).get("remainBeforeExit"));
            m.put("firstFuelRemainEntry", fuels.get(0).get("remainEntry"));
            m.put("firstFuelNorm", "");
        }

        // --- Расходы рейса (§12). Официальный бланк показывает ТОЛЬКО подтверждённые
        // бухгалтером расходы (Expense.confirmed): подтверждённая запись неизменяема
        // (антифрод, ExpenseService.confirm/delete), тогда как неподтверждённые — черновики
        // и в утверждённый документ попадать не должны. Итог группируется по валюте
        // (курс к TJS не применяем — не смешиваем валюты). Секция шаблона отсутствует,
        // если подтверждённых расходов нет (th:if по expenses в print/blocks :: expenses).
        List<Map<String, Object>> expenseRows = new ArrayList<>();
        Map<String, java.math.BigDecimal> amountByCcy = new LinkedHashMap<>();
        Map<String, java.math.BigDecimal> vatByCcy = new LinkedHashMap<>();
        for (Expense ex : expenses.findByWaybillIdOrderByCreatedAt(wb.getId())) {
            if (!ex.isConfirmed()) {
                continue;
            }
            String ccy = ex.getCurrency() == null ? "" : ex.getCurrency();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", EXPENSE_TYPE_NAMES.getOrDefault(ex.getExpenseType(), ex.getExpenseType()));
            row.put("amount", money(ex.getAmount()));
            row.put("currency", orDash(ccy));
            row.put("vat", ex.getVat() == null ? "—" : money(ex.getVat()));
            row.put("date", ex.getSpentAt() != null ? D.format(ex.getSpentAt()) : "—");
            expenseRows.add(row);
            if (ex.getAmount() != null) {
                amountByCcy.merge(ccy, ex.getAmount(), java.math.BigDecimal::add);
            }
            if (ex.getVat() != null) {
                vatByCcy.merge(ccy, ex.getVat(), java.math.BigDecimal::add);
            }
        }
        m.put("expenses", expenseRows);
        m.put("expensesTotal", totalByCurrency(amountByCcy));
        m.put("expensesVatTotal", totalByCurrency(vatByCcy));

        // --- Рабочие дни (для многодневных бланков 1-А / 2-Б / 3-С)
        List<Map<String, Object>> dayRows = new ArrayList<>();
        long totalLaps = 0;
        long totalDistance = 0;
        int totalMinutes = 0;
        for (WorkDay wd : days) {
            Map<String, Object> r = new LinkedHashMap<>();
            String date = wd.getWorkDate() != null ? D.format(wd.getWorkDate()) : "";
            String exT = wd.getExitTime() != null ? wd.getExitTime().format(TM) : "";
            String enT = wd.getEntryTime() != null ? wd.getEntryTime().format(TM) : "";
            r.put("date", date);
            r.put("exitTime", exT);
            r.put("entryTime", enT);
            r.put("exitDateTime", (date + " " + exT).trim());
            r.put("entryDateTime", (date + " " + enT).trim());
            r.put("exitAt", (date + " " + exT).trim());
            r.put("entryAt", (date + " " + enT).trim());
            r.put("counterExit", wd.getOdometerExit() != null ? wd.getOdometerExit() : "");
            r.put("counterEntry", wd.getOdometerEntry() != null ? wd.getOdometerEntry() : "");
            long dist = wd.getOdometerExit() != null && wd.getOdometerEntry() != null
                    ? Math.max(0, wd.getOdometerEntry() - wd.getOdometerExit()) : 0;
            r.put("distance", dist);
            long lap = wd.getLaps() != null ? wd.getLaps() : 0;
            r.put("laps", lap);
            int wmin = 0;
            if (wd.getExitTime() != null && wd.getEntryTime() != null) {
                wmin = wd.getEntryTime().toSecondOfDay() / 60 - wd.getExitTime().toSecondOfDay() / 60;
                if (wmin < 0) {
                    wmin += 24 * 60;
                }
            }
            r.put("workTime", wmin > 0 ? (wmin / 60) + ":" + String.format("%02d", wmin % 60) : "");
            r.put("fuelGiven", "");
            r.put("remainBeforeExit", "");
            r.put("remainEntry", "");
            r.put("revenue", num(wd.getRevenue()));
            dayRows.add(r);
            totalLaps += lap;
            totalDistance += dist;
            totalMinutes += wmin;
        }
        m.put("days", dayRows);
        m.put("workDays", dayRows);
        m.put("totalLaps", totalLaps);
        m.put("totalDistance", totalDistance);
        m.put("totalWorkTime", totalMinutes > 0 ? (totalMinutes / 60) + ":" + String.format("%02d", totalMinutes % 60) : "");
        m.put("laps", totalLaps > 0 ? totalLaps : orDash(""));

        // --- Подписи (из подписанных титулов)
        m.put("mechanicName", signerName(wb, "T3"));
        m.put("doctorName", signerName(wb, "T2"));
        m.put("dispatcherName", signerName(wb, "T1"));
        m.put("fuelEmployeeName", signerName(wb, "T1"));

        // --- Блок допуска бланка: подписант + вердикт + отпечаток ЭП из титулов
        // Т2 (медосмотр), Т3 (техконтроль), Т1 (выпуск/задание), Т6 (послерейсовый).
        m.put("doctorMark", titleMark(wb, "T2"));
        m.put("mechanicMark", titleMark(wb, "T3"));
        m.put("dispatcherMark", titleMark(wb, "T1"));
        m.put("postDoctorMark", titleMark(wb, "T6"));

        // --- Время работы двигателя / спецоборудования (графы «Вақти корӣ» бланка Т(1-АД)).
        m.put("engineWorkTime", orDash(str(m.get("totalWorkTime"))));
        m.put("specialEquipmentTime",
                orDash(firstNonBlank(str(td.get("conditionerHours")), str(td.get("specialWorkHours")))));

        // --- Таблица титулов и «дополнительные сведения» (для универсального бланка)
        List<Map<String, String>> extras = new ArrayList<>();
        addExtra(extras, "Вид перевозки", switch (str(td.get("shipmentKind"))) {
            case "PIECEWORK" -> "Корбайъ (сдельно)";
            case "HOURLY" -> "Соатбайъ (повременно)";
            default -> str(td.get("shipmentKind"));
        });
        addExtra(extras, "Вид услуги", str(td.get("serviceKind")));
        addExtra(extras, "Класс опасного груза (ADR)", str(td.get("adrClass")));
        addExtra(extras, "Наименование груза", str(td.get("cargoName")));
        addExtra(extras, "Страна визы", str(td.get("visaCountry")));
        addExtra(extras, "Номер дозвола (E-PERMIT)", str(td.get("permitNumber")));
        // Спецтехника (WB_SPECIAL) — единственный тип, идущий через универсальный бланк
        // (нет эталонной формы в оригинале), поэтому его поля не показаны нигде, кроме extras.
        addExtra(extras, "Вид работ", str(td.get("workType")));
        addExtra(extras, "Объект работ", str(td.get("workObject")));
        addExtra(extras, "Моточасы на выезде", str(td.get("motorHoursExit")));
        addExtra(extras, "Моточасы на возврате", str(td.get("motorHoursEntry")));
        if (wb.getSecondDriverRma() != null) {
            addExtra(extras, "Второй водитель (РМА)", wb.getSecondDriverRma());
        }
        m.put("extras", extras);

        List<Map<String, String>> signed = new ArrayList<>();
        for (WaybillTitle t : titles.findByWaybillIdOrderBySignedAt(wb.getId())) {
            Map<String, Object> data = t.getData() == null ? Map.of() : t.getData();
            Map<String, String> row = new LinkedHashMap<>();
            row.put("title", TITLE_LABEL.getOrDefault(t.getTitleType(), t.getTitleType()));
            row.put("verdict", orDash(str(data.get("verdict"))));
            row.put("signer", firstNonBlank(str(data.get("employeeName")), str(data.get("dispatcher")),
                    str(data.get("newDriverName")), t.getSignerRma()));
            row.put("signerRole", ROLE_LABEL.getOrDefault(t.getSignerRole(), t.getSignerRole()) + " · РМА " + t.getSignerRma());
            row.put("signedAt", DT.format(t.getSignedAt()));
            row.put("fingerprint", fingerprint(t.getSignature()));
            signed.add(row);
        }
        m.put("titles", signed);
        m.put("status", wb.getStatus().name());
        m.put("route", orDash(wb.getRoute()));
        m.put("odometer", m.get("counterExit") + " → " + m.get("counterEntry") + " (пробег " + distanceKm + " км)");
        m.put("orgName", str(org.get("name")));
        m.put("orgRma", str(org.get("rma")));
        m.put("driver", m.get("driverName") + " · РМА " + wb.getDriverRma());

        // --- Коэффициент нормирования топлива (графа «Коэффитсиент» бланков Т(1-АД) / 5Б-БМ).
        m.put("fuelCoefficient", calcK);
        m.put("fuelMultiplier", calcMultiplier);

        // --- QR + время печати
        String qr = null;
        if (wb.getNumber() != null) {
            qr = qrImage.dataUri(publicBaseUrl + "/verify/" + qrToken.sign(wb));
        }
        m.put("qr", qr);
        // Подпись водителя (одобренный документ SIGNATURE в master-data) — графа «Ронанда (имзо)»,
        // перенос legacy signature_attach (MIGRATION.md 2.6); нет/недоступна → пустая линия для подписи от руки.
        m.put("driverSignature", masterData.findDriverSignatureDataUri(wb.getDriverRma()).orElse(null));
        m.put("printedAt", DT.format(java.time.LocalDateTime.now()));
        m.put("generatedAt", DT.format(java.time.LocalDateTime.now()));

        // --- Водяной знак и отметка о формировании (B4, НЕ-ЭЦП часть).
        // Настройки категории print из master-data — тот же механизм, что и
        // malumotnoma_tariff_order (MasterDataClient.printSettings(): Map<String,String>,
        // при недоступности — пустая карта, бланк печатается в любом случае).
        // Изображение ЭЦП здесь НЕ реализуется (ждёт боевой CAdES/УЦ РТ).
        Map<String, String> printSettings = masterData.printSettings();
        m.put("showWatermark", "true".equalsIgnoreCase(printSettings.getOrDefault("show_watermark", "false")));
        String watermarkText = printSettings.get("watermark_text");
        m.put("watermarkText", watermarkText == null ? "" : watermarkText.trim());
        return m;
    }

    // ------------------------------------------------------------------

    private String signerName(Waybill wb, String titleType) {
        return titles.findByWaybillIdOrderBySignedAt(wb.getId()).stream()
                .filter(t -> titleType.equals(t.getTitleType()))
                .reduce((a, b) -> b)
                .map(t -> {
                    Map<String, Object> d = t.getData() == null ? Map.of() : t.getData();
                    return firstNonBlank(str(d.get("employeeName")), str(d.get("dispatcher")), t.getSignerRma());
                })
                .orElse("—");
    }

    /**
     * Отметка титула для блока допуска бланка: подписант, вердикт, отпечаток ЭП и время
     * подписания. {@code present=false}, если титул ещё не подписан (все поля — «—»).
     */
    private Map<String, Object> titleMark(Waybill wb, String titleType) {
        WaybillTitle t = titles.findByWaybillIdOrderBySignedAt(wb.getId()).stream()
                .filter(x -> titleType.equals(x.getTitleType()))
                .reduce((a, b) -> b).orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        if (t == null) {
            m.put("present", false);
            m.put("name", "—");
            m.put("verdict", "—");
            m.put("fingerprint", "—");
            m.put("signedAt", "—");
            return m;
        }
        Map<String, Object> d = t.getData() == null ? Map.of() : t.getData();
        m.put("present", true);
        m.put("name", orDash(firstNonBlank(str(d.get("employeeName")), str(d.get("dispatcher")), t.getSignerRma())));
        m.put("verdict", orDash(str(d.get("verdict"))));
        m.put("fingerprint", fingerprint(t.getSignature()));
        m.put("signedAt", DT.format(t.getSignedAt()));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOrEmpty(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static Map<String, Object> cargoOpRow(String operation, String executorName) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("operation", operation);
        row.put("executorName", orDash(executorName));
        row.put("method", "—");
        row.put("arrival", "—");
        row.put("departure", "—");
        row.put("downtimeMinutes", "—");
        row.put("additionalOps", "—");
        row.put("signature", "—");
        return row;
    }

    private static String trailerAt(Map<String, Object> td, int idx) {
        if (td.get("trailers") instanceof List<?> trs && trs.size() > idx && trs.get(idx) instanceof Map<?, ?> tr) {
            return str(tr.get("registrationNumber")) + " (" + str(tr.get("brand")) + ")";
        }
        return "—";
    }

    /**
     * «Самт» бланка 2-Б: реальное направление из справочника Direction (WB_TRUCK), а не пара
     * стран погрузки/разгрузки — та логика используется только у WB_TRUCK_INTL/5Б-БМ. Обращение
     * к master-data может упасть — бланк обязан печататься в любом случае (как расчёт K выше).
     */
    private String resolveDirectionTitle(Object directionIdRaw) {
        if (directionIdRaw == null || str(directionIdRaw).isBlank()) {
            return "—";
        }
        try {
            long id = Long.parseLong(str(directionIdRaw).trim());
            return masterData.findDirection(id)
                    .map(d -> str(d.get("title")))
                    .filter(s -> !s.isBlank())
                    .orElse("—");
        } catch (RuntimeException e) {
            log.debug("Печать: направление (directionId={}) недоступно ({})", directionIdRaw, e.toString());
            return "—";
        }
    }

    /**
     * «Ходуди фаъолият» бланков 2-Б/3-С (typeData.workRegions — массив кодов зон 1–7,
     * spec/data/dictionaries.yaml: regions) — человекочитаемые названия через запятую.
     */
    private static String workRegionsLabel(Object workRegionsRaw) {
        if (!(workRegionsRaw instanceof List<?> list) || list.isEmpty()) {
            return "—";
        }
        var names = new ArrayList<String>();
        for (Object item : list) {
            try {
                int code = Integer.parseInt(String.valueOf(item).trim());
                names.add(REGION_NAMES.getOrDefault(code, String.valueOf(code)));
            } catch (NumberFormatException ignored) {
                // некорректный код зоны — пропускаем, бланк не должен падать из-за мусора в данных
            }
        }
        return names.isEmpty() ? "—" : String.join(", ", names);
    }

    /** Вид услуги 3-С (typeData.serviceKind) — тот же перевод, что и в вебе (lib/i18n.tsx wb.svc.*). */
    private static String serviceKindLabel(String code) {
        return switch (code) {
            case "TAXI" -> "Такси (фармоишӣ)";
            case "ROUTE" -> "Хатсайр";
            case "HOURLY" -> "Соатбайъ";
            default -> "";
        };
    }

    private static String subjectTypeName(String code) {
        return switch (code) {
            case "PHYSICAL" -> "Шахси воқеӣ";
            case "IP" -> "Соҳибкори инфиродӣ";
            case "LEGAL" -> "Шахси ҳуқуқӣ";
            default -> "—";
        };
    }

    private static void addExtra(List<Map<String, String>> list, String label, String value) {
        if (value != null && !value.isBlank() && !"null".equals(value)) {
            list.add(Map.of("label", label, "value", value));
        }
    }

    private static String fingerprint(String signature) {
        if (signature == null || signature.isBlank()) {
            return "—";
        }
        String hex = signature.replaceAll("(?i)^(sha256|sha512|sha1|cades|stub)[:\\-]?", "")
                .replaceAll("[^A-Fa-f0-9]", "").toUpperCase();
        return hex.isEmpty() ? "—" : (hex.length() <= 16 ? hex : hex.substring(hex.length() - 16));
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static String num(java.math.BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    private static String trimNum(double v) {
        return java.math.BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    /** Денежная сумма для бланка — всегда 2 знака (в отличие от {@link #num}, чисел без .00). */
    private static String money(java.math.BigDecimal v) {
        return v == null ? "" : v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /** Итог расходов по валютам («1500.00 TJS + 200.00 USD») — курс к TJS не применяем. */
    private static String totalByCurrency(Map<String, java.math.BigDecimal> byCcy) {
        if (byCcy.isEmpty()) {
            return "—";
        }
        List<String> parts = new ArrayList<>();
        byCcy.forEach((ccy, sum) -> parts.add(money(sum) + (ccy == null || ccy.isBlank() ? "" : " " + ccy)));
        return String.join(" + ", parts);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    /**
     * Рамзи бор для печати (MIGRATION.md 2.25): номер из вложенного снимка груза {@code typeData.cargo.number},
     * иначе снимок {@code typeData.cargoNumber} (накладная), иначе «—». Целое печатается без дробной части.
     */
    static String cargoNumberOf(Map<String, Object> cargo, Map<String, Object> td) {
        String n = firstNonBlank(str(cargo == null ? null : cargo.get("number")),
                str(td == null ? null : td.get("cargoNumber")));
        if (n.isBlank()) {
            return "—";
        }
        return n.endsWith(".0") ? n.substring(0, n.length() - 2) : n;
    }
}
