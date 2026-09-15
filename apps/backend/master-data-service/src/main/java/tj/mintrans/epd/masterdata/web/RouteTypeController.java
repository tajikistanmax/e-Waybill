package tj.mintrans.epd.masterdata.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.masterdata.domain.RouteType;
import tj.mintrans.epd.masterdata.repository.RouteTypeRepository;
import tj.mintrans.epd.masterdata.service.AuditService;
import tj.mintrans.epd.masterdata.web.error.NotFoundException;

import java.util.List;
import java.util.UUID;

/**
 * Справочник типов маршрутов — «навъҳои хатсайр» (городской/пригородный/междугородный/
 * международный/транзитный). Классифицирует пассажирский маршрут по дальности сообщения.
 * Зеркало {@link RegionController}/{@link ExternalCityController}, ключ — числовой код.
 *
 * <p>Чтение (GET) — любой аутентифицированный (нужно в формах маршрута/фильтрах). Изменение
 * (POST upsert / DELETE) — только {@code SYSTEM_ADMIN}: справочник единый для платформы
 * (не организация-скоуп), те же роли, что и у прочих нац. справочников ({@link ClassifierController},
 * {@link RegionController}). Все правки пишутся в аудит (тип {@code ROUTE_TYPE}).</p>
 */
@RestController
@RequestMapping("/api/v1/route-types")
public class RouteTypeController {

    private final RouteTypeRepository routeTypes;
    private final AuditService audit;

    public RouteTypeController(RouteTypeRepository routeTypes, AuditService audit) {
        this.routeTypes = routeTypes;
        this.audit = audit;
    }

    public record RouteTypeRequest(
            @NotNull @Min(value = 1, message = "Код типа маршрута должен быть положительным") Short code,
            @NotBlank String nameRu,
            String nameTj,
            Short sortOrder,
            Boolean active) {
    }

    /** Список типов маршрутов в порядке сортировки/кода. */
    @GetMapping
    public List<RouteType> list() {
        return routeTypes.findAllByOrderBySortOrderAscCodeAsc();
    }

    /** Upsert по естественному ключу (числовой код); при существующей записи — обновление. */
    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<RouteType> upsert(@Valid @RequestBody RouteTypeRequest req) {
        var existing = routeTypes.findByCode(req.code());
        String oldValue = existing.map(RouteType::getNameRu).orElse(null); // до мутации
        var routeType = existing.orElseGet(RouteType::new);
        routeType.setCode(req.code());
        routeType.setNameRu(req.nameRu().trim());
        routeType.setNameTj(req.nameTj());
        if (req.sortOrder() != null) routeType.setSortOrder(req.sortOrder());
        routeType.setActive(req.active() == null || req.active());
        var saved = routeTypes.save(routeType);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "ROUTE_TYPE", String.valueOf(saved.getCode()), oldValue, saved.getNameRu());
        return ResponseEntity.status(existing.isPresent() ? HttpStatus.OK : HttpStatus.CREATED).body(saved);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        var routeType = routeTypes.findById(id)
                .orElseThrow(() -> new NotFoundException("Тип маршрута не найден"));
        routeTypes.delete(routeType);
        audit.record(AuditService.DELETE, "ROUTE_TYPE",
                String.valueOf(routeType.getCode()), routeType.getNameRu(), null);
        return ResponseEntity.noContent().build();
    }
}
