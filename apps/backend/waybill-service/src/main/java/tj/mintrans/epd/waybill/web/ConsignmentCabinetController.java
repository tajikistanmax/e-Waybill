package tj.mintrans.epd.waybill.web;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.print.PdfRenderService;
import tj.mintrans.epd.waybill.service.ConsignmentCabinetService;
import tj.mintrans.epd.waybill.service.WaybillService;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Кабинеты внешних пользователей накладных (MIGRATION.md 1.1/3.11 — legacy роли client_sender /
 * client_forwarder / customs_officer): список своих накладных, карточка, правка (отправитель/экспедитор),
 * печать борхата/СМР, таможенное подтверждение СМР (таможенник). Область — по клиентам из токена / роли,
 * не по организации.
 */
@RestController
@RequestMapping("/api/v1/consignments")
@PreAuthorize("hasAnyRole('CLIENT_SENDER','CLIENT_FORWARDER','CUSTOMS_OFFICER')")
public class ConsignmentCabinetController {

    private final ConsignmentCabinetService cabinet;

    public ConsignmentCabinetController(ConsignmentCabinetService cabinet) {
        this.cabinet = cabinet;
    }

    @GetMapping
    public ConsignmentCabinetService.PageResult list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "false") boolean unconfirmed,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return cabinet.list(new ConsignmentCabinetService.Filter(from, to, q, unconfirmed), page, size);
    }

    @GetMapping("/{id}")
    public Waybill get(@PathVariable UUID id) {
        return cabinet.get(id);
    }

    /** Правка накладной отправителем/экспедитором: груз, объём, статкод, документы, операции, число рейсов. */
    @PutMapping("/{id}")
    public Waybill update(@PathVariable UUID id, @RequestBody WaybillController.ConsignmentRequest req) {
        return cabinet.update(id, new WaybillService.ConsignmentUpdate(
                null, req.senderAddress(), null, req.receiverAddress(), null,
                req.cargoVolume(), req.cargoStatCode(), req.submittedDocuments(), null, null, req.cargoOperations(),
                null, null, null, null, req.cargoName(), null, req.tripsCount()));
    }

    /** Таможенник: «Тасдиқ кардан» — подтверждение СМР (повтор идемпотентен). */
    @PostMapping("/{id}/customs-confirm")
    @PreAuthorize("hasRole('CUSTOMS_OFFICER')")
    public Waybill confirmCustoms(@PathVariable UUID id) {
        return cabinet.confirmCustoms(id);
    }

    @GetMapping(value = "/{id}/print.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> printPdf(@PathVariable UUID id) {
        byte[] pdf = cabinet.printPdf(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + PdfRenderService.fileName(id.toString()) + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
