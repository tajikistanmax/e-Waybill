package tj.mintrans.epd.waybill.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.waybill.config.CurrentUser;
import tj.mintrans.epd.waybill.domain.ConsignmentNote;
import tj.mintrans.epd.waybill.print.WaybillPrintService;
import tj.mintrans.epd.waybill.service.ConsignmentNoteService;

import java.util.UUID;

/** Борхатҳо (накладные замимаи 1/2) путевого листа 2-Б — N на лист (legacy cargo_waybills). */
@RestController
@RequestMapping("/api/v1/waybills/{id}/consignment-notes")
public class ConsignmentNoteController {

    private final ConsignmentNoteService service;
    private final WaybillPrintService print;
    private final CurrentUser currentUser;

    public ConsignmentNoteController(ConsignmentNoteService service, WaybillPrintService print, CurrentUser currentUser) {
        this.service = service;
        this.print = print;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ConsignmentNoteService.NotesView list(@PathVariable UUID id) {
        return service.list(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN')")
    public ResponseEntity<ConsignmentNote> create(@PathVariable UUID id, @RequestBody ConsignmentNoteService.NoteData req) {
        var note = service.create(id, req, currentUser.username().orElse(null));
        return ResponseEntity.status(HttpStatus.CREATED).body(note);
    }

    @PutMapping("/{noteId}")
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN')")
    public ConsignmentNote update(@PathVariable UUID id, @PathVariable UUID noteId,
                                  @RequestBody ConsignmentNoteService.NoteData req) {
        return service.update(id, noteId, req);
    }

    @DeleteMapping("/{noteId}")
    @PreAuthorize("hasAnyRole('DISPATCHER','SYSTEM_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @PathVariable UUID noteId) {
        service.delete(id, noteId);
        return ResponseEntity.noContent().build();
    }

    /** Печатный борхат (замимаи 1/2) — один документ на строку, как legacy bill2b_attachment{1,2}. */
    @GetMapping(value = "/{noteId}/print.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> printPdf(@PathVariable UUID id, @PathVariable UUID noteId) {
        var note = service.get(id, noteId);
        byte[] body = print.renderNotePdf(id, note);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"borkhat-" + note.getNumber() + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(body);
    }
}
