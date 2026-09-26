package tj.mintrans.epd.masterdata.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tj.mintrans.epd.masterdata.service.RefSubjectService;

import java.util.Map;

/**
 * Регистрация субъектов внешней системой (КВД) — совместимость с legacy {@code POST/GET organization},
 * {@code transports}, {@code driver}, {@code employees} и {@code files/upload|delete} канала
 * {@code company.jwt:1} (сверка 25.09, G4). Правила полей и тела ответов — в {@link RefSubjectService}.
 *
 * <p>{@code GET transports} и {@code GET employees} с {@code organization_rma} — список организации
 * (legacy {@code TransportController::index}, {@code DoctorMechanicController::index}); без него те же
 * адреса — выгрузка изменений {@code ?page&updated_after} ({@link RefExportController}, G1).</p>
 */
@RestController
@RequestMapping("/api/v1/ref")
@PreAuthorize("hasAnyRole('API_INTEGRATOR','SYSTEM_ADMIN')")
public class RefSubjectController {

    private final RefSubjectService service;

    public RefSubjectController(RefSubjectService service) {
        this.service = service;
    }

    // ------------------------------------------------------------ организация

    @PostMapping("/organization")
    public ResponseEntity<Map<String, Object>> storeOrganization(@RequestBody Map<String, Object> body) {
        return saved(service.storeOrganization(body));
    }

    @GetMapping("/organization")
    public Map<String, Object> organization(@RequestParam(required = false) String rma,
                                            @RequestParam(required = false) String kpp,
                                            @RequestParam(name = "per_page", required = false) String perPage,
                                            @RequestParam(required = false) String page) {
        return service.listOrganizations(rma, kpp, perPage, page);
    }

    // ------------------------------------------------------------ транспорт

    @PostMapping("/transports")
    public ResponseEntity<Map<String, Object>> storeTransport(@RequestBody Map<String, Object> body) {
        return saved(service.storeTransport(body));
    }

    @GetMapping(value = "/transports", params = "organization_rma")
    public Map<String, Object> transports(@RequestParam("organization_rma") String organizationRma,
                                          @RequestParam(name = "organization_kpp", required = false) String kpp,
                                          @RequestParam(name = "per_page", required = false) String perPage,
                                          @RequestParam(required = false) String page) {
        return service.listTransports(organizationRma, kpp, perPage, page);
    }

    // ------------------------------------------------------------ водитель

    @PostMapping("/driver")
    public ResponseEntity<Map<String, Object>> storeDriver(@RequestBody Map<String, Object> body) {
        return saved(service.storeDriver(body));
    }

    @GetMapping("/driver")
    public Map<String, Object> drivers(@RequestParam(name = "organization_rma", required = false) String organizationRma,
                                       @RequestParam(name = "organization_kpp", required = false) String kpp,
                                       @RequestParam(name = "per_page", required = false) String perPage,
                                       @RequestParam(required = false) String page) {
        return service.listDrivers(organizationRma, kpp, perPage, page);
    }

    // ------------------------------------------------------------ сотрудник (врач, механик, диспетчер)

    @PostMapping("/employees")
    public ResponseEntity<Map<String, Object>> storeEmployee(@RequestBody Map<String, Object> body) {
        return saved(service.storeEmployee(body));
    }

    @GetMapping(value = "/employees", params = "organization_rma")
    public Map<String, Object> employees(@RequestParam("organization_rma") String organizationRma,
                                         @RequestParam(name = "organization_kpp", required = false) String kpp,
                                         @RequestParam(name = "per_page", required = false) String perPage,
                                         @RequestParam(required = false) String page) {
        return service.listEmployees(organizationRma, kpp, perPage, page);
    }

    // ------------------------------------------------------------ файлы

    @PostMapping(value = "/files/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(@RequestParam(value = "file", required = false) MultipartFile file,
                                                      @RequestParam(value = "file_type", required = false) String fileType) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.upload(file, fileType));
    }

    /** Удаление JSON-телом {@code {file_type, file_name}}. */
    @PostMapping(value = "/files/delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> deleteJson(@RequestBody Map<String, Object> body) {
        // Тело обязательно: иначе запрос без тела подходил бы под оба обработчика (consumes не проверяется).
        Object type = body.get("file_type");
        Object name = body.get("file_name");
        return service.delete(type == null ? null : type.toString(), name == null ? null : name.toString());
    }

    /** Удаление полями формы (как в legacy Laravel-запросе). */
    @PostMapping("/files/delete")
    public Map<String, Object> delete(@RequestParam(value = "file_type", required = false) String fileType,
                                      @RequestParam(value = "file_name", required = false) String fileName) {
        return service.delete(fileType, fileName);
    }

    /** Загруженный файл ({@code file_url} ответа загрузки) — только загрузившей его учётке. */
    @GetMapping("/files/{fileName:.+}")
    public ResponseEntity<byte[]> file(@PathVariable String fileName) {
        var u = service.file(fileName);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(u.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + u.getFileName() + "\"")
                .body(u.getData());
    }

    // ------------------------------------------------------------ ответы

    @ExceptionHandler(RefSubjectService.LegacyResponse.class)
    ResponseEntity<Map<String, Object>> legacy(RefSubjectService.LegacyResponse e) {
        return ResponseEntity.status(e.status()).body(e.body());
    }

    private static ResponseEntity<Map<String, Object>> saved(RefSubjectService.Saved s) {
        return ResponseEntity.status(s.created() ? HttpStatus.CREATED : HttpStatus.OK).body(s.body());
    }
}
