package tj.mintrans.epd.masterdata.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tj.mintrans.epd.masterdata.config.TenantScope;
import tj.mintrans.epd.masterdata.domain.Organization;
import tj.mintrans.epd.masterdata.repository.DriverRepository;
import tj.mintrans.epd.masterdata.repository.EmployeeRepository;
import tj.mintrans.epd.masterdata.repository.OrganizationRepository;
import tj.mintrans.epd.masterdata.repository.VehicleRepository;
import tj.mintrans.epd.masterdata.service.AuditService;
import tj.mintrans.epd.masterdata.web.error.NotFoundException;

import java.util.Optional;
import java.util.UUID;

/**
 * Перевод субъектов (водитель / транспорт / сотрудник) между организациями
 * (замечание владельца 22.09: «их надо открепить из одной фирмы, потом добавить в другую;
 * уже имеющегося заново не регистрируем»).
 *
 * <p>Поиск ведётся по ключу субъекта: водитель и сотрудник — РМА (ИНН), транспорт — госномер.
 * Поиск доступен администраторам и возвращает только минимум: кто это и за какой организацией
 * числится, — чтобы было видно, у кого запрашивать открепление.</p>
 *
 * <p>Правила закрепления: системный администратор переводит субъекта любой организации;
 * перевозчик (администратор компании/филиала, диспетчер) может закрепить за собой только
 * <b>свободного</b> субъекта (без организации) и открепить только своего. «Захват» чужого
 * субъекта запрещён (409) — как и в обычном сохранении карточки.</p>
 *
 * <p>Открепление НЕ удаляет субъекта и не меняет уже выписанные путевые листы: в них хранятся
 * снимки данных на момент выдачи.</p>
 */
@RestController
@RequestMapping("/api/v1/subjects")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','COMPANY_ADMIN','BRANCH_ADMIN','DISPATCHER')")
public class SubjectAssignmentController {

    /** Вид субъекта в адресе: drivers | vehicles | employees. */
    public enum SubjectKind { drivers, vehicles, employees }

    /** Краткая карточка найденного субъекта (без лишних персональных данных). */
    public record SubjectRef(UUID id, String kind, String key, String name,
                             String organizationRma, String organizationName, boolean attached) {
    }

    public record AttachRequest(@NotBlank String organizationRma) {
    }

    private final DriverRepository drivers;
    private final VehicleRepository vehicles;
    private final EmployeeRepository employees;
    private final OrganizationRepository organizations;
    private final TenantScope tenantScope;
    private final AuditService audit;

    public SubjectAssignmentController(DriverRepository drivers, VehicleRepository vehicles,
                                       EmployeeRepository employees, OrganizationRepository organizations,
                                       TenantScope tenantScope, AuditService audit) {
        this.drivers = drivers;
        this.vehicles = vehicles;
        this.employees = employees;
        this.organizations = organizations;
        this.tenantScope = tenantScope;
        this.audit = audit;
    }

    /** Поиск существующего субъекта по ключу: {@code ?key=461930031} или госномер для транспорта. */
    @GetMapping("/{kind}/lookup")
    public SubjectRef lookup(@PathVariable SubjectKind kind, @RequestParam String key) {
        String k = key == null ? "" : key.trim();
        if (k.isEmpty()) {
            throw unprocessable("Укажите ИНН (РМА) или госномер для поиска");
        }
        return switch (kind) {
            case drivers -> drivers.findByRma(k).map(d -> ref(kind, d.getId(), d.getRma(), d.getFullName(), d.getOrganizationId()))
                    .orElseThrow(() -> new NotFoundException("Водитель с ИНН %s не найден".formatted(k)));
            case vehicles -> vehicles.findByRegistrationNumber(k.toUpperCase())
                    .map(v -> ref(kind, v.getId(), v.getRegistrationNumber(), v.getBrand(), v.getOrganizationId()))
                    .orElseThrow(() -> new NotFoundException("ТС с госномером %s не найдено".formatted(k.toUpperCase())));
            case employees -> employees.findByRma(k).map(e -> ref(kind, e.getId(), e.getRma(), e.getName(), e.getOrganizationId()))
                    .orElseThrow(() -> new NotFoundException("Сотрудник с ИНН %s не найден".formatted(k)));
        };
    }

    /** Закрепить субъекта за организацией (перевод из другой организации — только системный администратор). */
    @PostMapping("/{kind}/{id}/attach")
    public SubjectRef attach(@PathVariable SubjectKind kind, @PathVariable UUID id,
                             @Valid @RequestBody AttachRequest req) {
        Organization target = organizations.findByRma(req.organizationRma().trim())
                .orElseThrow(() -> new NotFoundException("Организация не найдена"));
        if (tenantScope.isBounded() && !tenantScope.canWrite(target.getRma())) {
            throw new AccessDeniedException("Доступ только к своим организациям");
        }
        UUID current = currentOrganization(kind, id);
        if (tenantScope.isBounded() && current != null && !current.equals(target.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Субъект закреплён за другой организацией — сначала его должна открепить она");
        }
        SubjectRef out = setOrganization(kind, id, target.getId());
        audit.record(AuditService.UPDATE, auditType(kind), out.key(),
                current == null ? null : organizationName(current), target.getName());
        return out;
    }

    /** Открепить субъекта от организации (субъект остаётся в базе и может быть закреплён за другой). */
    @PostMapping("/{kind}/{id}/detach")
    public SubjectRef detach(@PathVariable SubjectKind kind, @PathVariable UUID id) {
        UUID current = currentOrganization(kind, id);
        if (current == null) {
            throw unprocessable("Субъект уже не закреплён за организацией");
        }
        if (tenantScope.isBounded() && !tenantScope.organizationIds().contains(current)) {
            throw new AccessDeniedException("Доступ только к своим организациям");
        }
        SubjectRef out = setOrganization(kind, id, null);
        audit.record(AuditService.UPDATE, auditType(kind), out.key(), organizationName(current), null);
        return out;
    }

    // ------------------------------------------------------------------ вспомогательное

    private UUID currentOrganization(SubjectKind kind, UUID id) {
        return switch (kind) {
            case drivers -> drivers.findById(id).orElseThrow(() -> new NotFoundException("Водитель не найден")).getOrganizationId();
            case vehicles -> vehicles.findById(id).orElseThrow(() -> new NotFoundException("ТС не найдено")).getOrganizationId();
            case employees -> employees.findById(id).orElseThrow(() -> new NotFoundException("Сотрудник не найден")).getOrganizationId();
        };
    }

    private SubjectRef setOrganization(SubjectKind kind, UUID id, UUID organizationId) {
        switch (kind) {
            case drivers -> {
                var d = drivers.findById(id).orElseThrow(() -> new NotFoundException("Водитель не найден"));
                d.setOrganizationId(organizationId);
                // Закрепление за ТС действует только внутри организации — при переводе снимаем.
                d.setAssignedVehicleId(null);
                var saved = drivers.save(d);
                return ref(kind, saved.getId(), saved.getRma(), saved.getFullName(), saved.getOrganizationId());
            }
            case vehicles -> {
                var v = vehicles.findById(id).orElseThrow(() -> new NotFoundException("ТС не найдено"));
                v.setOrganizationId(organizationId);
                var saved = vehicles.save(v);
                return ref(kind, saved.getId(), saved.getRegistrationNumber(), saved.getBrand(), saved.getOrganizationId());
            }
            default -> {
                var e = employees.findById(id).orElseThrow(() -> new NotFoundException("Сотрудник не найден"));
                e.setOrganizationId(organizationId);
                var saved = employees.save(e);
                return ref(kind, saved.getId(), saved.getRma(), saved.getName(), saved.getOrganizationId());
            }
        }
    }

    private SubjectRef ref(SubjectKind kind, UUID id, String key, String name, UUID organizationId) {
        Optional<Organization> org = organizationId == null ? Optional.empty() : organizations.findById(organizationId);
        return new SubjectRef(id, kind.name(), key, name,
                org.map(Organization::getRma).orElse(null),
                org.map(Organization::getName).orElse(null),
                organizationId != null);
    }

    private String organizationName(UUID organizationId) {
        return organizations.findById(organizationId).map(Organization::getName).orElse(null);
    }

    private static String auditType(SubjectKind kind) {
        return switch (kind) {
            case drivers -> "DRIVER";
            case vehicles -> "VEHICLE";
            case employees -> "EMPLOYEE";
        };
    }

    private static ResponseStatusException unprocessable(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
