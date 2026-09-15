package tj.mintrans.epd.waybill.web;

import org.springframework.http.ContentDisposition;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.format.annotation.DateTimeFormat;
import tj.mintrans.epd.waybill.domain.Malumotnoma;
import tj.mintrans.epd.waybill.domain.MalumotnomaRoute;
import tj.mintrans.epd.waybill.print.MalumotnomaPrintService;
import tj.mintrans.epd.waybill.print.ReportXlsxWriter;
import tj.mintrans.epd.waybill.service.MalumotnomaService;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Справки пассажирам (маълумотнома): выдача, реестр, печать, отчёт по кассирам.
 */
@RestController
@RequestMapping("/api/v1/malumotnomas")
@PreAuthorize("hasAnyRole('DISPATCHER','ACCOUNTANT','COMPANY_ADMIN','BRANCH_ADMIN','SYSTEM_ADMIN','MINTRANS_ANALYST')")
public class MalumotnomaController {

    private final MalumotnomaService service;
    private final MalumotnomaPrintService print;
    private final ReportXlsxWriter xlsx;

    public MalumotnomaController(MalumotnomaService service, MalumotnomaPrintService print, ReportXlsxWriter xlsx) {
        this.service = service;
        this.print = print;
        this.xlsx = xlsx;
    }

    @GetMapping
    public List<Malumotnoma> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public Malumotnoma get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('DISPATCHER','ACCOUNTANT','COMPANY_ADMIN','BRANCH_ADMIN','SYSTEM_ADMIN')")
    public ResponseEntity<Malumotnoma> create(@RequestBody MalumotnomaService.CreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('DISPATCHER','ACCOUNTANT','COMPANY_ADMIN','BRANCH_ADMIN','SYSTEM_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/{id}/print.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> printPdf(@PathVariable UUID id) {
        byte[] pdf = print.renderPdf(id);
        ContentDisposition cd = ContentDisposition.inline()
                .filename("malumotnoma-" + id + ".pdf", StandardCharsets.US_ASCII).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    // ------------------------------------------------------------- отчёт

    @GetMapping("/report")
    public MalumotnomaService.Report report(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String issuerRma) {
        return service.report(from, to, issuerRma);
    }

    @GetMapping("/report.xlsx")
    public ResponseEntity<byte[]> reportXlsx(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String issuerRma) {
        byte[] body = xlsx.writeMalumotnoma(service.report(from, to, issuerRma));
        ContentDisposition cd = ContentDisposition.attachment()
                .filename("malumotnoma-report-" + from + "_" + to + ".xlsx", StandardCharsets.US_ASCII).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    // ------------------------------------------------------------- маршруты справок

    @GetMapping("/routes")
    public List<MalumotnomaRoute> routes(@RequestParam(required = false, defaultValue = "false") boolean all) {
        return service.routes(all);
    }

    @PostMapping("/routes")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<MalumotnomaRoute> createRoute(@RequestBody MalumotnomaService.RouteRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.saveRoute(null, req));
    }

    @PutMapping("/routes/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public MalumotnomaRoute updateRoute(@PathVariable UUID id, @RequestBody MalumotnomaService.RouteRequest req) {
        return service.saveRoute(id, req);
    }

    @DeleteMapping("/routes/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> deleteRoute(@PathVariable UUID id) {
        service.deleteRoute(id);
        return ResponseEntity.noContent().build();
    }
}
