package tj.mintrans.epd.waybill.calc;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Сводка предупреждений расчёта на время отчётного прохода по путевым листам.
 *
 * <p>Расчёт ({@code tj.mintrans.epd.waybill.calc.*}) пишет WARN на каждый лист, у которого не
 * заполнены справочные данные: тариф маршрута, доля дохода, год выпуска ТС, норматив марки…
 * Для одного листа (карточка, печать) это полезно. Но месячный отчёт по всей платформе считает
 * десятки тысяч архивных листов, и лог получал ~220 тыс. одинаковых строк за один отчёт
 * (находка 23.09.2026) — настоящие ошибки в нём терялись.</p>
 *
 * <p>Внутри {@link #open(String) прохода} (его открывает {@code WaybillPeriodScan}) такие WARN
 * не пишутся построчно, а считаются по шаблону сообщения; при закрытии прохода выводится ОДНА
 * строка WARN с количеством по каждому виду. Построчно они остаются доступны на уровне DEBUG
 * логгера {@code tj.mintrans.epd.waybill.calc}. Вне прохода (расчёт одного листа) поведение
 * прежнее — обычный WARN.</p>
 *
 * <p>Механизм — {@link TurboFilter} Logback: код расчёта не меняется, фильтр подавляет только
 * WARN логгеров пакета расчёта и только в потоке, где открыт проход.</p>
 */
public final class CalcLogSummary {

    static final String CALC_PACKAGE = "tj.mintrans.epd.waybill.calc";
    private static final String FILTER_NAME = "calc-log-summary";
    /** Сколько видов предупреждений перечислять в итоговой строке. */
    private static final int TOP = 8;

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(CalcLogSummary.class);
    private static final ThreadLocal<Summary> CURRENT = new ThreadLocal<>();

    private CalcLogSummary() {
    }

    /** Установить фильтр в контекст Logback (идемпотентно). Без Logback — ничего не делает. */
    public static synchronized void install() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx)) {
            return;
        }
        boolean present = ctx.getTurboFilterList().stream().anyMatch(f -> FILTER_NAME.equals(f.getName()));
        if (!present) {
            SummaryFilter filter = new SummaryFilter();
            filter.setName(FILTER_NAME);
            filter.start();
            ctx.addTurboFilter(filter);
        }
    }

    /**
     * Открыть проход. Вложенный вызов в том же потоке не создаёт новую сводку — счёт идёт
     * во внешнюю, итог пишет внешний проход.
     *
     * @param what подпись прохода для итоговой строки (период, охват)
     */
    public static Scope open(String what) {
        install();
        if (CURRENT.get() != null) {
            return Scope.NOOP;
        }
        Summary s = new Summary(what);
        CURRENT.set(s);
        return new Scope(s);
    }

    /** Открытый проход текущего потока (для тестов). */
    static Summary current() {
        return CURRENT.get();
    }

    /** Счётчики одного прохода. */
    static final class Summary {
        final String what;
        final Map<String, long[]> byTemplate = new LinkedHashMap<>();
        long items;
        long suppressed;

        Summary(String what) {
            this.what = what;
        }

        void count(String template) {
            suppressed++;
            byTemplate.computeIfAbsent(template, k -> new long[1])[0]++;
        }

        String render() {
            List<Map.Entry<String, long[]>> entries = new ArrayList<>(byTemplate.entrySet());
            entries.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            long rest = 0;
            for (Map.Entry<String, long[]> e : entries) {
                if (shown < TOP) {
                    if (shown > 0) {
                        sb.append("; ");
                    }
                    sb.append('«').append(e.getKey()).append("» × ").append(e.getValue()[0]);
                    shown++;
                } else {
                    rest += e.getValue()[0];
                }
            }
            if (rest > 0) {
                sb.append("; прочие виды (").append(entries.size() - TOP).append(") × ").append(rest);
            }
            return sb.toString();
        }
    }

    /** Закрытие прохода пишет итоговую строку (если было что подавлять). */
    public static final class Scope implements AutoCloseable {
        static final Scope NOOP = new Scope(null);
        private final Summary summary;

        private Scope(Summary summary) {
            this.summary = summary;
        }

        /** Учесть очередной обработанный лист (для итоговой строки). */
        public void item() {
            if (summary != null) {
                summary.items++;
            }
        }

        @Override
        public void close() {
            if (summary == null) {
                return;
            }
            CURRENT.remove();
            if (summary.suppressed > 0) {
                log.warn("Отчётный проход {}: листов {}, предупреждений расчёта о незаполненных данных {} "
                                + "(построчно — уровень DEBUG логгера {}): {}",
                        summary.what, summary.items, summary.suppressed, CALC_PACKAGE, summary.render());
            }
        }
    }

    /** Подавляет WARN пакета расчёта в потоке с открытым проходом и считает их. */
    static final class SummaryFilter extends TurboFilter {
        @Override
        public FilterReply decide(Marker marker, Logger logger, Level level, String format,
                                  Object[] params, Throwable t) {
            // format == null — проверка isWarnEnabled(), а не само сообщение: её не трогаем.
            if (level != Level.WARN || format == null || logger == null
                    || !logger.getName().startsWith(CALC_PACKAGE)) {
                return FilterReply.NEUTRAL;
            }
            Summary s = CURRENT.get();
            if (s == null) {
                return FilterReply.NEUTRAL;
            }
            s.count(format);
            if (logger.isDebugEnabled()) {
                logger.debug(format, params);
            }
            return FilterReply.DENY;
        }
    }
}
