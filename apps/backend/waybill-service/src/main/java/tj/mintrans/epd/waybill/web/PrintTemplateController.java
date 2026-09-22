package tj.mintrans.epd.waybill.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
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
import tj.mintrans.epd.waybill.print.PrintTemplateService;

import java.util.List;
import java.util.UUID;

/**
 * Редактор печатных шаблонов бланков (MIGRATION.md 7.1 / 10.4) — только платформенный администратор:
 * список встроенных шаблонов, текст, сохранение переопределения (с пробным рендером), сброс, предпросмотр PDF.
 */
@RestController
@RequestMapping("/api/v1/print-templates")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class PrintTemplateController {

    private final PrintTemplateService templates;

    public PrintTemplateController(PrintTemplateService templates) {
        this.templates = templates;
    }

    public record SaveRequest(@NotBlank String content, @Size(max = 500) String note, UUID validateWith) {
    }

    public record PreviewRequest(@NotBlank String content, UUID waybillId) {
    }

    @GetMapping
    public List<PrintTemplateService.Summary> list() {
        return templates.list();
    }

    @GetMapping("/{name}")
    public PrintTemplateService.Content get(@PathVariable String name) {
        return templates.get(name);
    }

    @PutMapping("/{name}")
    public PrintTemplateService.Summary save(@PathVariable String name, @Valid @RequestBody SaveRequest req) {
        return templates.save(name, req.content(), req.note(), req.validateWith());
    }

    @DeleteMapping("/{name}")
    public PrintTemplateService.Summary reset(@PathVariable String name) {
        return templates.reset(name);
    }

    @PostMapping(value = "/{name}/preview", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> preview(@PathVariable String name, @Valid @RequestBody PreviewRequest req) {
        byte[] pdf = templates.preview(name, req.content(), req.waybillId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"preview-" + name + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
