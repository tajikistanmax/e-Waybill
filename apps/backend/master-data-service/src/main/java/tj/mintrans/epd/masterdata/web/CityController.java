package tj.mintrans.epd.masterdata.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tj.mintrans.epd.masterdata.domain.City;
import tj.mintrans.epd.masterdata.repository.CityRepository;

import java.util.List;

/**
 * Справочник городов/районов (только чтение) — источник подсказок для поля «Город» организации.
 * Данные засеяны миграцией V54 (перенос из боевого MinTransRT), одинаковы для всех арендаторов.
 */
@RestController
@RequestMapping("/api/v1/cities")
public class CityController {

    private final CityRepository cities;

    public CityController(CityRepository cities) {
        this.cities = cities;
    }

    /** Список городов; при указании region — только выбранного региона (1..7). */
    @GetMapping
    public List<City> list(@RequestParam(required = false) Short region) {
        return region != null
                ? cities.findByRegionIdOrderByNameAsc(region)
                : cities.findAllByOrderByRegionIdAscNameAsc();
    }
}
