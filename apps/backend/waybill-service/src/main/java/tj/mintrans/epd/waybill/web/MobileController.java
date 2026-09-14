package tj.mintrans.epd.waybill.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.waybill.client.MasterDataClient;
import tj.mintrans.epd.waybill.config.CurrentUser;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillRequest;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.repository.WaybillTitleRepository;
import tj.mintrans.epd.waybill.service.QrTokenService;
import tj.mintrans.epd.waybill.service.WaybillRequestService;
import tj.mintrans.epd.waybill.service.WaybillService;
import tj.mintrans.epd.waybill.web.error.ApiErrors.ForbiddenException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Мобильное API водителя — компактные ответы для нативного приложения (Android/iOS).
 *
 * <p>Водитель со смартфона: свой профиль, список своих ПЛ, карточка ПЛ с QR для
 * предъявления инспектору, подтверждение получения ПЛ (READY → ISSUED), подача и
 * отмена заявки на ПЛ. Аутентификация — тот же Keycloak (realm epd), роль DRIVER;
 * область видимости — по claim {@code rma} субъекта, а не по организации.</p>
 */
@RestController
@RequestMapping("/api/v1/mobile")
@PreAuthorize("hasAnyRole('DRIVER','SYSTEM_ADMIN')")
public class MobileController {

    private final WaybillRepository waybills;
    private final WaybillTitleRepository titles;
    private final WaybillService waybillService;
    private final WaybillRequestService requests;
    private final tj.mintrans.epd.waybill.repository.WaybillAttachmentRepository attachments;
    private final QrTokenService qr;
    private final MasterDataClient masterData;
    private final CurrentUser currentUser;

    public MobileController(WaybillRepository waybills, WaybillTitleRepository titles,
                            WaybillService waybillService, WaybillRequestService requests,
                            tj.mintrans.epd.waybill.repository.WaybillAttachmentRepository attachments,
                            QrTokenService qr, MasterDataClient masterData, CurrentUser currentUser) {
        this.waybills = waybills;
        this.titles = titles;
        this.waybillService = waybillService;
        this.requests = requests;
        this.attachments = attachments;
        this.qr = qr;
        this.masterData = masterData;
        this.currentUser = currentUser;
    }

    // ------------------------------------------------------------ DTO

    public record MobileProfile(String rma, String fullName, String phone,
                                String organizationRma, String organizationName,
                                String licenseNumber, String licenseValidTo, String medCertValidTo) {
    }

    public record MobileWaybill(String id, String number, String type, String typeLabel,
                                String status, String route, String schedule,
                                String vehicleRegNumber, String vehicleBrand,
                                String validFrom, String validTo,
                                boolean medPassed, boolean techPassed,
                                Integer odometerExit, Integer odometerEntry, Integer mileage) {
    }

    public record MobileMark(String type, String verdict, String by, String at) {
    }

    public record MobileAttachment(String id, String docType, String title, String fileName, long sizeBytes) {
    }

    public record MobileWaybillDetail(MobileWaybill waybill, List<MobileMark> marks,
                                      List<MobileAttachment> attachments,
                                      String qrJws, String verifyUrlPath, List<String> allowedActions) {
    }

    public record MobileRequestBody(String waybillType, String vehicleRegNumber,
                                    String requestedFrom, Integer odometer, String route, String notes) {
    }

    // ------------------------------------------------------------ профиль

    @GetMapping("/me")
    public MobileProfile me() {
        String rma = driverRma();
        Map<String, Object> d = masterData.findDriver(rma).orElse(Map.of());
        String orgRma = currentUser.organizationRma().orElse(str(d.get("organizationRma")));
        String orgName = orgRma == null ? null
                : masterData.findOrganization(orgRma).map(o -> str(o.get("name"))).orElse(null);
        return new MobileProfile(rma,
                firstNonBlank(str(d.get("fullName")), currentUser.username().orElse(rma)),
                str(d.get("phone")), orgRma, orgName,
                str(d.get("licenseNumber")), str(d.get("licenseValidTo")), str(d.get("medCertValidTo")));
    }

    // ------------------------------------------------------------ путевые листы

    @GetMapping("/waybills")
    public List<MobileWaybill> list(@RequestParam(required = false) String status) {
        String rma = driverRma();
        return waybills.findForDriver(rma).stream()
                .filter(w -> status == null || status.isBlank() || w.getStatus().name().equalsIgnoreCase(status))
                .map(this::toMobile)
                .toList();
    }

    /** Текущий ПЛ водителя: активный → выданный → готовый к получению (для главного экрана). */
    @GetMapping("/waybills/current")
    public MobileWaybillDetail current() {
        String rma = driverRma();
        var list = waybills.findForDriver(rma);
        Waybill wb = pick(list, "ACTIVE");
        if (wb == null) wb = pick(list, "ISSUED");
        if (wb == null) wb = pick(list, "RETURNED");
        if (wb == null) wb = pick(list, "READY");
        if (wb == null) {
            throw new NotFoundException("Действующего путевого листа нет");
        }
        return detail(wb.getId());
    }

    @GetMapping("/waybills/{id}")
    public MobileWaybillDetail detail(@PathVariable UUID id) {
        Waybill wb = ownWaybill(id);
        List<MobileMark> marks = titles.findByWaybillIdOrderBySignedAt(id).stream()
                .map(t -> {
                    Map<String, Object> data = t.getData() == null ? Map.of() : t.getData();
                    return new MobileMark(t.getTitleType(), str(data.get("verdict")),
                            firstNonBlank(str(data.get("employeeName")), t.getSignerRma()),
                            t.getSignedAt() == null ? null : t.getSignedAt().toString());
                })
                .toList();
        List<MobileAttachment> att = attachments.findByWaybillIdOrderByUploadedAtDesc(id).stream()
                .map(m -> new MobileAttachment(m.getId().toString(), m.getDocType(),
                        m.getTitle(), m.getFileName(), m.getSizeBytes()))
                .toList();
        String jws = wb.getNumber() != null ? qr.sign(wb) : null;
        List<String> actions = new java.util.ArrayList<>();
        if (wb.getStatus().name().equals("READY")) {
            actions.add("ACCEPT");
        }
        if (wb.getStatus().name().equals("READY") || wb.getStatus().name().equals("AWAITING_PAYMENT")) {
            actions.add("VIEW");
        }
        return new MobileWaybillDetail(toMobile(wb), marks, att, jws,
                jws != null ? "/verify/" + jws : null, actions);
    }

    /** Скачивание вложения к ПЛ (CMR / накладная / фото груза) — водитель видит вложения своего листа. */
    @GetMapping("/waybills/{id}/attachments/{attachmentId}")
    public org.springframework.http.ResponseEntity<byte[]> attachment(@PathVariable UUID id,
                                                                      @PathVariable UUID attachmentId) {
        ownWaybill(id);
        var a = attachments.findById(attachmentId)
                .filter(x -> x.getWaybillId().equals(id))
                .orElseThrow(() -> new NotFoundException("Вложение не найдено"));
        org.springframework.http.ContentDisposition cd = org.springframework.http.ContentDisposition.attachment()
                .filename(a.getFileName(), java.nio.charset.StandardCharsets.UTF_8).build();
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(org.springframework.http.MediaType.parseMediaType(a.getContentType()))
                .body(a.getData());
    }

    @GetMapping("/waybills/{id}/qr")
    public Map<String, String> waybillQr(@PathVariable UUID id) {
        Waybill wb = ownWaybill(id);
        if (wb.getNumber() == null) {
            throw new NotFoundException("QR доступен после присвоения номера");
        }
        return Map.of("jws", qr.sign(wb), "verifyUrlPath", "/verify/" + qr.sign(wb));
    }

    /** Подтверждение получения ПЛ водителем (READY → ISSUED). */
    @PostMapping("/waybills/{id}/accept")
    public MobileWaybill accept(@PathVariable UUID id) {
        Waybill wb = waybillService.acceptByDriver(id, driverRma());
        return toMobile(wb);
    }

    // ------------------------------------------------------------ заявки на ПЛ

    @GetMapping("/waybill-requests")
    public List<WaybillRequest> myRequests() {
        return requests.listForDriver();
    }

    @PostMapping("/waybill-requests")
    public WaybillRequest createRequest(@RequestBody MobileRequestBody body) {
        java.time.LocalDate from = null;
        if (body.requestedFrom() != null && !body.requestedFrom().isBlank()) {
            try {
                from = java.time.LocalDate.parse(body.requestedFrom());
            } catch (java.time.format.DateTimeParseException ignored) {
                // некорректная дата — заявка без желаемого срока
            }
        }
        return requests.create(
                tj.mintrans.epd.waybill.domain.WaybillType.valueOf(body.waybillType()),
                body.vehicleRegNumber(), from, body.odometer(), null, body.route(), null, body.notes());
    }

    @PostMapping("/waybill-requests/{id}/cancel")
    public WaybillRequest cancelRequest(@PathVariable UUID id) {
        return requests.cancelOwn(id);
    }

    // ------------------------------------------------------------ helpers

    private String driverRma() {
        return currentUser.rma()
                .orElseThrow(() -> new ForbiddenException("В токене нет РМА водителя"));
    }

    private Waybill ownWaybill(UUID id) {
        String rma = driverRma();
        Waybill wb = waybills.findById(id).orElseThrow(() -> new NotFoundException("Путевой лист не найден"));
        if (!rma.equals(wb.getDriverRma()) && !rma.equals(wb.getSecondDriverRma())
                && !currentUser.hasRole("SYSTEM_ADMIN")) {
            throw new NotFoundException("Путевой лист не найден");
        }
        return wb;
    }

    private static Waybill pick(List<Waybill> list, String status) {
        return list.stream().filter(w -> w.getStatus().name().equals(status)).findFirst().orElse(null);
    }

    private MobileWaybill toMobile(Waybill w) {
        Map<String, Object> veh = w.getVehicleSnapshot() == null ? Map.of() : w.getVehicleSnapshot();
        Integer mileage = w.getOdometerExit() != null && w.getOdometerEntry() != null
                ? Math.max(0, w.getOdometerEntry() - w.getOdometerExit()) : null;
        return new MobileWaybill(
                w.getId().toString(), w.getNumber(),
                w.getWaybillType().name(), w.getWaybillType().legacyForm(),
                w.getStatus().name(), w.getRoute(), w.getSchedule(),
                w.getVehicleRegNumber(), str(veh.get("brand")),
                w.getValidFrom() == null ? null : w.getValidFrom().toString(),
                w.getValidTo() == null ? null : w.getValidTo().toString(),
                w.isMedPassed(), w.isTechPassed(),
                w.getOdometerExit(), w.getOdometerEntry(), mileage);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
