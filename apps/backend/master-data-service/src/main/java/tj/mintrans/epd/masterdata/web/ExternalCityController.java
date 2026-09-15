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
import tj.mintrans.epd.masterdata.domain.ExternalCity;
import tj.mintrans.epd.masterdata.repository.ExternalCityRepository;
import tj.mintrans.epd.masterdata.service.AuditService;
import tj.mintrans.epd.masterdata.web.error.NotFoundException;

import java.util.List;
import java.util.UUID;

/**
 * Справочник ВНЕШНИХ (зарубежных) городов — источник подсказок для поля «Город» в международных
 * путевых листах и СМР. Зеркало {@link CityController} (справочник городов РТ), но привязка не к
 * региону, а к стране ({@code country} — ISO 3166-1 alpha-2, код элемента классификатора COUNTRY).
 *
 * <p>Чтение (GET) — любой аутентифицированный (нужно в формах межд. ПЛ). Изменение (POST upsert /
 * DELETE) — только {@code SYSTEM_ADMIN}: справочник единый для платформы (не организация-скоуп),
 * те же роли, что и у прочих нац. справочников ({@link ClassifierController},
 * {@link LegacyReferenceController}). Все правки пишутся в аудит.</p>
 */
@RestController
@RequestMapping("/api/v1/external-cities")
public class ExternalCityController {

    private final ExternalCityRepository cities;
    private final AuditService audit;

    public ExternalCityController(ExternalCityRepository cities, AuditService audit) {
        this.cities = cities;
        this.audit = audit;
    }

    public record ExternalCityRequest(
            @NotBlank String countryCode,
            @NotBlank String nameRu,
            String nameTj,
            Short sortOrder,
            Boolean active) {
    }

    /** Список внешних городов; при указании country — только выбранной страны (ISO alpha-2). */
    @GetMapping
    public List<ExternalCity> list(@RequestParam(required = false) String country) {
        return country != null && !country.isBlank()
                ? cities.findByCountryCodeOrderBySortOrderAscNameRuAsc(country.trim().toUpperCase())
                : cities.findAllByOrderByCountryCodeAscSortOrderAscNameRuAsc();
    }

    /** Upsert по естественному ключу (country_code, name_ru); при существующей записи — обновление. */
    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ExternalCity> upsert(@Valid @RequestBody ExternalCityRequest req) {
        String cc = req.countryCode().trim().toUpperCase();
        var existing = cities.findByCountryCodeAndNameRuIgnoreCase(cc, req.nameRu().trim());
        String oldValue = existing.map(ExternalCity::getNameTj).orElse(null); // до мутации
        var city = existing.orElseGet(ExternalCity::new);
        city.setCountryCode(cc);
        city.setNameRu(req.nameRu().trim());
        city.setNameTj(req.nameTj());
        if (req.sortOrder() != null) city.setSortOrder(req.sortOrder());
        city.setActive(req.active() == null || req.active());
        var saved = cities.save(city);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "EXTERNAL_CITY", cc + ":" + saved.getNameRu(), oldValue, saved.getNameTj());
        return ResponseEntity.status(existing.isPresent() ? HttpStatus.OK : HttpStatus.CREATED).body(saved);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        var city = cities.findById(id)
                .orElseThrow(() -> new NotFoundException("Внешний город не найден"));
        cities.delete(city);
        audit.record(AuditService.DELETE, "EXTERNAL_CITY",
                city.getCountryCode() + ":" + city.getNameRu(), city.getNameRu(), null);
        return ResponseEntity.noContent().build();
    }
}
