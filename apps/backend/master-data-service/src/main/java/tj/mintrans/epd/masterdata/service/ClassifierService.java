package tj.mintrans.epd.masterdata.service;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import tj.mintrans.epd.masterdata.config.CacheConfig;
import tj.mintrans.epd.masterdata.domain.Classifier;
import tj.mintrans.epd.masterdata.repository.ClassifierRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Сервисный слой классификаторов: держит кэширование чтения по категории и
 * инвалидацию при любой записи. Аннотации кэша стоят именно здесь (не на контроллере),
 * чтобы работало проксирование Spring — вызовы приходят из контроллера (другой бин).
 *
 * <p>Классификаторы (страны, классы ADR, виды дозволов, типы ПЛ) редко меняются, но
 * читаются на каждой форме ПЛ — идеальный кандидат на кэш.</p>
 */
@Service
public class ClassifierService {

    private final ClassifierRepository repository;

    public ClassifierService(ClassifierRepository repository) {
        this.repository = repository;
    }

    /** Активные элементы категории. Кэш classifiers, ключ = категория. */
    @Cacheable(cacheNames = CacheConfig.CLASSIFIERS_CACHE, key = "#category")
    public List<Classifier> listActiveByCategory(String category) {
        return repository.findByCategoryAndActiveTrueOrderBySortOrderAscCodeAsc(category);
    }

    /**
     * Полный список категории (включая скрытые). Отдельный ключ «категория:all»,
     * чтобы не смешивать с набором активных под тем же именем категории.
     */
    @Cacheable(cacheNames = CacheConfig.CLASSIFIERS_CACHE, key = "#category + ':all'")
    public List<Classifier> listAllByCategory(String category) {
        return repository.findByCategoryOrderBySortOrderAscCodeAsc(category);
    }

    /** Точечный поиск для upsert — не кэшируем (нужна актуальная запись под мутацию). */
    public Optional<Classifier> findByCategoryAndCode(String category, String code) {
        return repository.findByCategoryAndCode(category, code);
    }

    /** Поиск по id для delete — не кэшируем. */
    public Optional<Classifier> findById(UUID id) {
        return repository.findById(id);
    }

    /**
     * Сохранение (create/update). Инвалидируем ВЕСЬ кэш классификаторов (allEntries=true):
     * одна запись может влиять на несколько ключей (активные/all той же категории),
     * а полная очистка проще и безопаснее точечной — исключает отдачу устаревших списков.
     */
    @CacheEvict(cacheNames = CacheConfig.CLASSIFIERS_CACHE, allEntries = true)
    public Classifier save(Classifier classifier) {
        return repository.save(classifier);
    }

    /** Удаление — та же полная инвалидация кэша классификаторов. */
    @CacheEvict(cacheNames = CacheConfig.CLASSIFIERS_CACHE, allEntries = true)
    public void delete(Classifier classifier) {
        repository.delete(classifier);
    }
}
