package tj.mintrans.epd.waybill.print;

import org.springframework.stereotype.Component;
import org.thymeleaf.IEngineConfiguration;
import org.thymeleaf.cache.NonCacheableCacheEntryValidity;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ITemplateResolver;
import org.thymeleaf.templateresolver.TemplateResolution;
import org.thymeleaf.templateresource.StringTemplateResource;
import tj.mintrans.epd.waybill.repository.PrintTemplateOverrideRepository;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Thymeleaf-резолвер печатных шаблонов из БД (MIGRATION.md 7.1): для имени {@code print/<name>} сначала
 * ищется переопределение в {@code print_template_override}; нет строки — {@code null}, и Thymeleaf берёт
 * встроенный шаблон из classpath (стандартный резолвер Spring Boot, порядок ниже — см. application.yml).
 *
 * <p>Результат помечен некэшируемым: правка администратора видна следующим же PDF без перезапуска. Для
 * предпросмотра несохранённого текста служит {@link #withTemporary} — подмена в пределах текущего потока.</p>
 */
@Component
public class DbTemplateResolver implements ITemplateResolver {

    static final String PREFIX = "print/";
    private static final ThreadLocal<Map<String, String>> TEMPORARY = new ThreadLocal<>();

    private final PrintTemplateOverrideRepository overrides;

    public DbTemplateResolver(PrintTemplateOverrideRepository overrides) {
        this.overrides = overrides;
    }

    /** Выполнить {@code body}, подставляя для перечисленных имён шаблонов (без префикса) временный текст. */
    static <T> T withTemporary(Map<String, String> temporary, Supplier<T> body) {
        TEMPORARY.set(temporary);
        try {
            return body.get();
        } finally {
            TEMPORARY.remove();
        }
    }

    @Override
    public String getName() {
        return "print-template-override-db";
    }

    @Override
    public Integer getOrder() {
        return 1;
    }

    @Override
    public TemplateResolution resolveTemplate(IEngineConfiguration configuration, String ownerTemplate,
                                              String template, Map<String, Object> templateResolutionAttributes) {
        if (template == null || !template.startsWith(PREFIX)) {
            return null;
        }
        String name = template.substring(PREFIX.length());
        Map<String, String> temp = TEMPORARY.get();
        String content = temp != null ? temp.get(name) : null;
        if (content == null) {
            content = overrides.findById(name).map(o -> o.getContent()).orElse(null);
        }
        if (content == null) {
            return null; // → следующий резолвер (встроенный classpath-шаблон)
        }
        return new TemplateResolution(new StringTemplateResource(content), true, TemplateMode.HTML, false,
                NonCacheableCacheEntryValidity.INSTANCE);
    }
}
