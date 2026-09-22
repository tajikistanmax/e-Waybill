package tj.mintrans.epd.waybill.print;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.TemplateEngine;
import tj.mintrans.epd.waybill.config.CurrentUser;
import tj.mintrans.epd.waybill.domain.PrintTemplateOverride;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.repository.PrintTemplateOverrideRepository;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Редактируемые печатные шаблоны (MIGRATION.md 7.1 / 10.4): список встроенных бланков, текущий текст
 * (переопределение из БД или встроенный), сохранение переопределения с проверкой рендера, сброс к встроенному,
 * предпросмотр несохранённого текста на реальном ПЛ.
 */
@Service
public class PrintTemplateService {

    /** Встроенные шаблоны {@code templates/print/<name>.html}: имя → виды ПЛ, на которых их можно проверить/предпросмотреть. */
    static final Map<String, Set<WaybillType>> BUILT_IN = new LinkedHashMap<>();

    static {
        BUILT_IN.put("waybill1ad", Set.of(WaybillType.WB_BUS, WaybillType.WB_TROLLEYBUS));
        BUILT_IN.put("waybill1a", Set.of(WaybillType.WB_MINIBUS));
        BUILT_IN.put("waybill3c", Set.of(WaybillType.WB_CAR, WaybillType.WB_TAXI));
        BUILT_IN.put("waybill2b", Set.of(WaybillType.WB_TRUCK, WaybillType.WB_DANGEROUS));
        BUILT_IN.put("waybill2b-attachment", Set.of(WaybillType.WB_TRUCK, WaybillType.WB_DANGEROUS));
        BUILT_IN.put("waybill5bbm", Set.of(WaybillType.WB_TRUCK_INTL));
        BUILT_IN.put("cmr", Set.of(WaybillType.WB_TRUCK_INTL));
        BUILT_IN.put("waybill4mbm", Set.of(WaybillType.WB_PAX_INTL));
        BUILT_IN.put("waybill", Set.of(WaybillType.WB_SPECIAL));
        BUILT_IN.put("blocks", Set.of());     // фрагменты — проверяются на любом ПЛ
        BUILT_IN.put("styles", Set.of());
        BUILT_IN.put("malumotnoma", null);    // справка — предпросмотр по ПЛ не применим
    }

    static final int MAX_BYTES = 512 * 1024;

    /** Выражения Thymeleaf/SpEL, которым не место в бланке: доступ к бинам, типам, конструкторам, препроцессинг. */
    private static final Pattern EXPRESSION = Pattern.compile("[$*#@~]\\{([^}]*)}");
    private static final Pattern FORBIDDEN = Pattern.compile(
            "(^|[^\\w])@[A-Za-z_]|\\bT\\s*\\(|\\bnew\\s+[A-Za-z_][\\w.]*\\s*\\(|\\.getClass\\s*\\(|\\.class\\b"
                    + "|Runtime|ProcessBuilder|forName\\s*\\(|__\\$",
            Pattern.CASE_INSENSITIVE);

    public record Summary(String name, boolean overridden, String note, String updatedBy, OffsetDateTime updatedAt,
                          boolean previewable) {
    }

    public record Content(String name, boolean overridden, String content, String builtIn, String note,
                          String updatedBy, OffsetDateTime updatedAt) {
    }

    private final PrintTemplateOverrideRepository overrides;
    private final WaybillRepository waybills;
    private final WaybillPrintService printService;
    private final TemplateEngine templateEngine;
    private final CurrentUser currentUser;

    public PrintTemplateService(PrintTemplateOverrideRepository overrides, WaybillRepository waybills,
                                WaybillPrintService printService, TemplateEngine templateEngine,
                                CurrentUser currentUser) {
        this.overrides = overrides;
        this.waybills = waybills;
        this.printService = printService;
        this.templateEngine = templateEngine;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<Summary> list() {
        Map<String, PrintTemplateOverride> byName = new LinkedHashMap<>();
        overrides.findAll().forEach(o -> byName.put(o.getName(), o));
        List<Summary> out = new ArrayList<>();
        for (String name : BUILT_IN.keySet()) {
            PrintTemplateOverride o = byName.get(name);
            out.add(new Summary(name, o != null, o == null ? null : o.getNote(), o == null ? null : o.getUpdatedBy(),
                    o == null ? null : o.getUpdatedAt(), BUILT_IN.get(name) != null));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Content get(String name) {
        requireBuiltIn(name);
        String builtIn = builtInContent(name);
        Optional<PrintTemplateOverride> o = overrides.findById(name);
        return new Content(name, o.isPresent(), o.map(PrintTemplateOverride::getContent).orElse(builtIn), builtIn,
                o.map(PrintTemplateOverride::getNote).orElse(null), o.map(PrintTemplateOverride::getUpdatedBy).orElse(null),
                o.map(PrintTemplateOverride::getUpdatedAt).orElse(null));
    }

    /**
     * Сохранить переопределение: имя из встроенного списка, размер ≤ 512 КБ, без опасных выражений, и текст
     * должен успешно отрисоваться на реальном ПЛ ({@code validateWith} либо последний подходящий ПЛ; если такого
     * нет — сохраняется без пробного рендера).
     */
    @Transactional
    public Summary save(String name, String content, String note, UUID validateWith) {
        requireBuiltIn(name);
        assertContentAcceptable(content);
        Optional<Waybill> sample = sampleWaybill(name, validateWith);
        if (sample.isPresent()) {
            try {
                render(name, content, sample.get().getId());
            } catch (UnprocessableException e) {
                throw e;
            } catch (RuntimeException e) {
                Waybill s = sample.get();
                String label = s.getNumber() != null ? "№ " + s.getNumber() : "id " + s.getId();
                throw new UnprocessableException("Шаблон не отрисовался на ПЛ " + label + ": " + rootMessage(e));
            }
        }
        PrintTemplateOverride o = overrides.findById(name).orElseGet(PrintTemplateOverride::new);
        o.setName(name);
        o.setContent(content);
        o.setNote(note == null || note.isBlank() ? null : note.trim());
        o.setUpdatedBy(currentUser.username().orElse(null));
        o.setUpdatedAt(OffsetDateTime.now());
        overrides.save(o);
        templateEngine.clearTemplateCacheFor(DbTemplateResolver.PREFIX + name);
        return new Summary(name, true, o.getNote(), o.getUpdatedBy(), o.getUpdatedAt(), BUILT_IN.get(name) != null);
    }

    /** Сброс к встроенному шаблону (удаление переопределения). */
    @Transactional
    public Summary reset(String name) {
        requireBuiltIn(name);
        overrides.findById(name).ifPresent(overrides::delete);
        templateEngine.clearTemplateCacheFor(DbTemplateResolver.PREFIX + name);
        return new Summary(name, false, null, null, null, BUILT_IN.get(name) != null);
    }

    /** Предпросмотр несохранённого текста на ПЛ {@code waybillId} — PDF. */
    @Transactional(readOnly = true)
    public byte[] preview(String name, String content, UUID waybillId) {
        requireBuiltIn(name);
        assertContentAcceptable(content);
        if (BUILT_IN.get(name) == null) {
            throw new UnprocessableException("Предпросмотр шаблона «" + name + "» по путевому листу не поддерживается");
        }
        return render(name, content, waybillId);
    }

    /** Рендер с временной подменой текста шаблона, без регистрации печати; фрагменты (blocks/styles) — через бланк вида ПЛ. */
    private byte[] render(String name, String content, UUID waybillId) {
        templateEngine.clearTemplateCacheFor(DbTemplateResolver.PREFIX + name);
        Set<WaybillType> types = BUILT_IN.get(name);
        String template = types == null || types.isEmpty() ? null : DbTemplateResolver.PREFIX + name;
        return DbTemplateResolver.withTemporary(Map.of(name, content), () -> printService.renderPreview(waybillId, template));
    }

    /** ПЛ для проверки/предпросмотра: явный, иначе последний не-архивный подходящего вида (фрагменты — любой). */
    private Optional<Waybill> sampleWaybill(String name, UUID explicit) {
        if (explicit != null) {
            return Optional.of(waybills.findById(explicit)
                    .orElseThrow(() -> new NotFoundException("ПЛ для проверки не найден")));
        }
        Set<WaybillType> types = BUILT_IN.get(name);
        if (types == null) {
            return Optional.empty();
        }
        return types.isEmpty()
                ? waybills.findFirstBySourceNotOrderByCreatedAtDesc("MIGRATED")
                : waybills.findFirstByWaybillTypeInAndSourceNotOrderByCreatedAtDesc(types, "MIGRATED");
    }

    // ------------------------------------------------------------------ проверки (тестируются)

    static void requireBuiltIn(String name) {
        if (name == null || !BUILT_IN.containsKey(name)) {
            throw new NotFoundException("Неизвестный печатный шаблон: " + name);
        }
    }

    /** Размер и запрет опасных выражений (бины, типы, конструкторы, препроцессинг) внутри {@code ${…}}/{@code *{…}} и т. п. */
    static void assertContentAcceptable(String content) {
        if (content == null || content.isBlank()) {
            throw new UnprocessableException("Текст шаблона пуст");
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new UnprocessableException("Шаблон больше 512 КБ");
        }
        String bad = firstForbiddenExpression(content);
        if (bad != null) {
            throw new UnprocessableException("Недопустимое выражение в шаблоне: " + bad);
        }
    }

    /** Первое запрещённое выражение (текст внутри скобок) или null. */
    static String firstForbiddenExpression(String content) {
        if (content.contains("__$")) {
            return "__${…}__ (препроцессинг)";
        }
        Matcher m = EXPRESSION.matcher(content);
        while (m.find()) {
            String expr = m.group(1);
            if (FORBIDDEN.matcher(expr).find()) {
                return expr.length() > 80 ? expr.substring(0, 80) + "…" : expr;
            }
        }
        return null;
    }

    static String builtInContent(String name) {
        try {
            return new ClassPathResource("templates/print/" + name + ".html").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new NotFoundException("Встроенный шаблон не найден: " + name);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        return msg.length() > 300 ? msg.substring(0, 300) + "…" : msg;
    }
}
