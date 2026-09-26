package tj.mintrans.epd.masterdata.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.server.ResponseStatusException;
import tj.mintrans.epd.masterdata.domain.Region;
import tj.mintrans.epd.masterdata.repository.CityRepository;
import tj.mintrans.epd.masterdata.repository.OrganizationRepository;
import tj.mintrans.epd.masterdata.repository.RegionRepository;
import tj.mintrans.epd.masterdata.service.AuditService;
import tj.mintrans.epd.masterdata.web.error.NotFoundException;

import java.util.List;
import java.util.UUID;

/**
 * Справочник регионов (зон деятельности) РТ — «минтақаҳо». Задел под региональную отчётность/фильтры;
 * до этого регион жил только как числовой код region_id (1..7) без справочной таблицы.
 * Зеркало {@link CityController}/{@link ExternalCityController}, ключ — числовой код 1..7.
 *
 * <p>Чтение (GET) — любой аутентифицированный (нужно в фильтрах/формах). Изменение (POST upsert /
 * DELETE) — только {@code SYSTEM_ADMIN}: справочник единый для платформы (не организация-скоуп),
 * те же роли, что и у прочих нац. справочников ({@link ClassifierController},
 * {@link ExternalCityController}). Все правки пишутся в аудит.</p>
 */
@RestController
@RequestMapping("/api/v1/regions")
public class RegionController {

    private final RegionRepository regions;
    private final CityRepository cities;
    private final OrganizationRepository organizations;
    private final AuditService audit;

    public RegionController(RegionRepository regions, CityRepository cities, OrganizationRepository organizations,
                            AuditService audit) {
        this.regions = regions;
        this.cities = cities;
        this.organizations = organizations;
        this.audit = audit;
    }

    public record RegionRequest(
            @NotNull @Min(value = 1, message = "Код региона: 1–7")
            @Max(value = 7, message = "Код региона: 1–7") Short code,
            @NotBlank String nameRu,
            String nameTj,
            /* «Рамз» — статистический код зоны, как в «Роҳхат» (сверка 25.09, E6). */
            @Size(max = 10, message = "Рамз — не длиннее 10 символов") String statCode,
            Short sortOrder,
            Boolean active) {
    }

    /** Список регионов в порядке сортировки/кода. */
    @GetMapping
    public List<Region> list() {
        return regions.findAllByOrderBySortOrderAscCodeAsc();
    }

    /** Upsert по естественному ключу (числовой код 1..7); при существующей записи — обновление. */
    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Region> upsert(@Valid @RequestBody RegionRequest req) {
        var existing = regions.findByCode(req.code());
        String oldValue = existing.map(Region::getNameRu).orElse(null); // до мутации
        var region = existing.orElseGet(Region::new);
        region.setCode(req.code());
        region.setNameRu(req.nameRu().trim());
        region.setNameTj(req.nameTj());
        region.setStatCode(req.statCode() == null || req.statCode().isBlank() ? null : req.statCode().trim());
        if (req.sortOrder() != null) region.setSortOrder(req.sortOrder());
        region.setActive(req.active() == null || req.active());
        var saved = regions.save(region);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "REGION", String.valueOf(saved.getCode()), oldValue, saved.getNameRu());
        return ResponseEntity.status(existing.isPresent() ? HttpStatus.OK : HttpStatus.CREATED).body(saved);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        var region = regions.findById(id)
                .orElseThrow(() -> new NotFoundException("Регион не найден"));
        // На код региона ссылаются города и организации (region_id без FK) — удаление оставило бы их
        // без региона в отчётах. Такой регион можно только отключить.
        long used = cities.countByRegionId(region.getCode()) + organizations.countByRegionId(region.getCode());
        if (used > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Регион используется (" + used + " городов и организаций) — удалить нельзя, отключите его");
        }
        regions.delete(region);
        audit.record(AuditService.DELETE, "REGION",
                String.valueOf(region.getCode()), region.getNameRu(), null);
        return ResponseEntity.noContent().build();
    }
}
