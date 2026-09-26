package tj.mintrans.epd.waybill.web;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.waybill.print.WaybillPrintService;
import tj.mintrans.epd.waybill.service.ConsignmentNoteRegistryService;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Реестр борхатов 2-Б (legacy «Борхатҳои замимаи 1/2»): Минтранс — все, перевозчик — свои организации,
 * грузоотправитель и экспедитор — свои борхаты. Печать борхата из реестра — с той же областью видимости.
 */
@RestController
@RequestMapping("/api/v1/consignment-notes")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','MINTRANS_ANALYST','DISPATCHER','COMPANY_ADMIN','BRANCH_ADMIN','ACCOUNTANT',"
        + "'CLIENT_SENDER','CLIENT_FORWARDER')")
public class ConsignmentNoteRegistryController {

    private final ConsignmentNoteRegistryService registry;
    private final WaybillPrintService print;

    public ConsignmentNoteRegistryController(ConsignmentNoteRegistryService registry, WaybillPrintService print) {
        this.registry = registry;
        this.print = print;
    }

    @GetMapping
    public ConsignmentNoteRegistryService.PageResult list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String organizationRma,
            @RequestParam(required = false) String sender,
            @RequestParam(required = false) String forwarder,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer kind,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return registry.list(new ConsignmentNoteRegistryService.Filter(from, to, organizationRma, sender, forwarder, q, kind),
                page, size);
    }

    @GetMapping(value = "/{noteId}/print.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> print(@PathVariable UUID noteId) {
        var ref = registry.noteWithWaybill(noteId);
        byte[] body = print.renderNotePdf(ref.waybill(), ref.note());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"borkhat-" + ref.note().getNumber() + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(body);
    }
}
