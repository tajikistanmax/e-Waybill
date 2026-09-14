package tj.mintrans.epd.waybill.web;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.waybill.print.WaybillPrintService;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Печатный бланк путевого листа в PDF.
 *
 * <p>Область видимости — как у чтения ПЛ: {@code WaybillPrintService} зовёт
 * {@code WaybillService.get}, который сам применяет мультиарендное ограничение
 * (не-админ / водитель видят только свои). Отдельного {@code @PreAuthorize} не нужно —
 * ровно как у {@code GET /api/v1/waybills/{id}}.</p>
 */
@RestController
@RequestMapping("/api/v1/waybills")
public class PrintController {

    private final WaybillPrintService print;

    public PrintController(WaybillPrintService print) {
        this.print = print;
    }

    @GetMapping(value = "/{id}/print.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> printPdf(@PathVariable UUID id) {
        byte[] pdf = print.renderPdf(id);
        ContentDisposition cd = ContentDisposition.inline()
                .filename(print.fileName(id), StandardCharsets.US_ASCII)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    /** Накладная (приложение к 2-Б) — только для WB_TRUCK/WB_DANGEROUS. */
    @GetMapping(value = "/{id}/print-attachment.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> printAttachmentPdf(@PathVariable UUID id) {
        byte[] pdf = print.renderAttachmentPdf(id);
        ContentDisposition cd = ContentDisposition.inline()
                .filename(print.attachmentFileName(id), StandardCharsets.US_ASCII)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    /** CMR — международная товарно-транспортная накладная к 5Б-БМ. */
    @GetMapping(value = "/{id}/print-cmr.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> printCmrPdf(@PathVariable UUID id) {
        byte[] pdf = print.renderCmrPdf(id);
        ContentDisposition cd = ContentDisposition.inline()
                .filename(print.cmrFileName(id), StandardCharsets.US_ASCII)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
