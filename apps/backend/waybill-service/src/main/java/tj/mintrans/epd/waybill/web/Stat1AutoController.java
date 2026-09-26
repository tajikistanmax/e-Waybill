package tj.mintrans.epd.waybill.web;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.waybill.print.ReportXlsxWriter;
import tj.mintrans.epd.waybill.service.Stat1AutoService;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * Статформа «1-авто» (legacy тип отчёта 4 «Авто»; сверка 25.09, D4). Доступ — как у типовых отчётов
 * ({@link ReportController}): перевозчик по своей области, платформа — по выбранной организации или всем.
 */
@RestController
@RequestMapping("/api/v1/reports")
@PreAuthorize("hasAnyRole('DISPATCHER','ACCOUNTANT','COMPANY_ADMIN','BRANCH_ADMIN','SYSTEM_ADMIN','MINTRANS_ANALYST')")
public class Stat1AutoController {

    private final Stat1AutoService service;
    private final ReportXlsxWriter xlsx;

    public Stat1AutoController(Stat1AutoService service, ReportXlsxWriter xlsx) {
        this.service = service;
        this.xlsx = xlsx;
    }

    @GetMapping("/stat-1auto")
    public Stat1AutoService.Report stat1Auto(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String organizationRma,
            @RequestParam(defaultValue = "false") boolean ytd) {
        return service.build(from, to, organizationRma, ytd);
    }

    @GetMapping("/stat-1auto.xlsx")
    public ResponseEntity<byte[]> stat1AutoXlsx(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String organizationRma,
            @RequestParam(defaultValue = "false") boolean ytd) {
        byte[] body = xlsx.writeStat1Auto(service.build(from, to, organizationRma, ytd));
        ContentDisposition cd = ContentDisposition.attachment()
                .filename("1-avto-" + from + "_" + to + ".xlsx", StandardCharsets.US_ASCII).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }
}
