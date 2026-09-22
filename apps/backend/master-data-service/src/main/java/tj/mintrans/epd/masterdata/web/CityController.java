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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.masterdata.domain.City;
import tj.mintrans.epd.masterdata.repository.CityRepository;
import tj.mintrans.epd.masterdata.service.AuditService;
import tj.mintrans.epd.masterdata.web.error.NotFoundException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Справочник городов/районов — источник подсказок для поля «Город» организации и маршрута.
 * Данные засеяны миграцией V54 (перенос из боевого MinTransRT), одинаковы для всех арендаторов.
 * Чтение — любой авторизованный; изменение и удаление — только системный администратор
 * (справочник платформенный, как остальные национальные; замечание владельца 22.09 — в старой
 * платформе города велись в админке, у нас раздел был только для чтения).
 */
@RestController
@RequestMapping("/api/v1/cities")
public class CityController {

    public record CityRequest(
            UUID id,
            @NotNull @Min(value = 1, message = "Регион: 1–7") @Max(value = 7, message = "Регион: 1–7") Short regionId,
            @Size(max = 20) String code,
            @NotBlank(message = "Укажите название города/района") String name) {
    }

    private final CityRepository cities;
    private final AuditService audit;

    public CityController(CityRepository cities, AuditService audit) {
        this.cities = cities;
        this.audit = audit;
    }

    /** Список городов; при указании region — только выбранного региона (1..7). */
    @GetMapping
    public List<City> list(@RequestParam(required = false) Short region) {
        return region != null
                ? cities.findByRegionIdOrderByNameAsc(region)
                : cities.findAllByOrderByRegionIdAscNameAsc();
    }

    /** Добавление города или правка существующего (при заданном id — именно этой записи). */
    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<City> upsert(@Valid @RequestBody CityRequest req) {
        Optional<City> existing = req.id() == null ? Optional.empty()
                : Optional.of(cities.findById(req.id())
                        .orElseThrow(() -> new NotFoundException("Город не найден: " + req.id())));
        String oldValue = existing.map(City::getName).orElse(null);
        City city = existing.orElseGet(City::new);
        city.setRegionId(req.regionId());
        city.setCode(req.code() == null || req.code().isBlank() ? null : req.code().trim());
        city.setName(req.name().trim());
        City saved = cities.save(city);
        audit.record(existing.isPresent() ? AuditService.UPDATE : AuditService.CREATE,
                "CITY", saved.getName(), oldValue, saved.getName());
        return ResponseEntity.status(existing.isPresent() ? HttpStatus.OK : HttpStatus.CREATED).body(saved);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        City city = cities.findById(id).orElseThrow(() -> new NotFoundException("Город не найден: " + id));
        cities.delete(city);
        audit.record(AuditService.DELETE, "CITY", city.getName(), city.getName(), null);
        return ResponseEntity.noContent().build();
    }
}
