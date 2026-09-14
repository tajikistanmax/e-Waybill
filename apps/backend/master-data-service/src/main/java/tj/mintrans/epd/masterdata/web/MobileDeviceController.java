package tj.mintrans.epd.masterdata.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.masterdata.config.CurrentUser;
import tj.mintrans.epd.masterdata.domain.MobileDevice;
import tj.mintrans.epd.masterdata.repository.MobileDeviceRepository;
import tj.mintrans.epd.masterdata.service.AuditService;
import tj.mintrans.epd.masterdata.web.error.NotFoundException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Реестр авторизованных МОБИЛЬНЫХ УСТРОЙСТВ водителей — перенос legacy-справочника «Телефонҳо»
 * (phone_infos). Показывает, с какого устройства (бренд/модель) водитель какой корхоны
 * авторизовался в мобильном приложении.
 *
 * <p>Чтение (GET) — мультиарендно (по образцу {@link DocumentExpiryController}): тенант видит
 * только устройства своей организации (claim {@code organization_rma}); платформенная роль
 * (SYSTEM_ADMIN / MINTRANS_ANALYST / API_INTEGRATOR) — все, с необязательным фильтром по РМА
 * организации. Изменение (POST upsert / DELETE) — только {@code SYSTEM_ADMIN} (по образцу
 * {@link ExternalCityController}); все правки пишутся в аудит (тип {@code MOBILE_DEVICE}).</p>
 *
 * <p>Этап 1 — ручной CRUD. Автозаполнение из мобильного приложения при авторизации — этап 2
 * (здесь НЕ реализовано).</p>
 */
@RestController
@RequestMapping("/api/v1/mobile-devices")
public class MobileDeviceController {

    private final MobileDeviceRepository devices;
    private final AuditService audit;
    private final CurrentUser currentUser;

    public MobileDeviceController(MobileDeviceRepository devices, AuditService audit, CurrentUser currentUser) {
        this.devices = devices;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    /**
     * Тело upsert. {@code id} задан → обновление существующей записи; отсутствует → создание.
     * Естественного ключа среди полей нет (у водителя может быть несколько устройств, у устройства
     * нет отдельного идентификатора в этих полях), поэтому upsert идёт по первичному ключу.
     */
    public record MobileDeviceRequest(
            UUID id,
            @NotBlank String organizationRma,
            String driverRma,
            @NotBlank String driverName,
            String brand,
            @NotBlank String model,
            OffsetDateTime authorizedAt) {
    }

    /**
     * Список устройств. Тенант — только своя организация (по токену); платформенная роль — все,
     * либо конкретной организации при указании {@code organizationRma}.
     */
    @GetMapping
    public List<MobileDevice> list(@RequestParam(required = false) String organizationRma) {
        if (currentUser.isTenantScoped()) {
            return currentUser.organizationRma()
                    .map(devices::findByOrganizationRmaOrderByAuthorizedAtDesc)
                    .orElseGet(List::of);
        }
        return (organizationRma == null || organizationRma.isBlank())
                ? devices.findAllByOrderByAuthorizedAtDesc()
                : devices.findByOrganizationRmaOrderByAuthorizedAtDesc(organizationRma.trim());
    }

    /** Upsert по первичному ключу: с id — обновление, без id — создание. */
    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<MobileDevice> upsert(@Valid @RequestBody MobileDeviceRequest req) {
        boolean isNew = req.id() == null;
        MobileDevice device = isNew ? new MobileDevice()
                : devices.findById(req.id())
                        .orElseThrow(() -> new NotFoundException("Мобильное устройство не найдено"));
        String oldValue = isNew ? null : join(device.getBrand(), device.getModel()); // до мутации
        device.setOrganizationRma(req.organizationRma().trim());
        device.setDriverRma(blankToNull(req.driverRma()));
        device.setDriverName(req.driverName().trim());
        device.setBrand(blankToNull(req.brand()));
        device.setModel(req.model().trim());
        if (req.authorizedAt() != null) device.setAuthorizedAt(req.authorizedAt());
        var saved = devices.save(device);
        audit.record(isNew ? AuditService.CREATE : AuditService.UPDATE, "MOBILE_DEVICE",
                entityKey(saved), oldValue, join(saved.getBrand(), saved.getModel()));
        return ResponseEntity.status(isNew ? HttpStatus.CREATED : HttpStatus.OK).body(saved);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        var device = devices.findById(id)
                .orElseThrow(() -> new NotFoundException("Мобильное устройство не найдено"));
        devices.delete(device);
        audit.record(AuditService.DELETE, "MOBILE_DEVICE",
                entityKey(device), join(device.getBrand(), device.getModel()), null);
        return ResponseEntity.noContent().build();
    }

    /** Ключ записи для аудита: РМА корхоны : табель водителя (или «-») : модель. */
    private static String entityKey(MobileDevice d) {
        return d.getOrganizationRma() + ":" + (d.getDriverRma() == null ? "-" : d.getDriverRma()) + ":" + d.getModel();
    }

    private static String join(String brand, String model) {
        return ((brand == null ? "" : brand) + " " + (model == null ? "" : model)).trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
