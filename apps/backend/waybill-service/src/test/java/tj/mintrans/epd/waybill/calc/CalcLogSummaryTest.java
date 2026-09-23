package tj.mintrans.epd.waybill.calc;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Сводка предупреждений расчёта за отчётный проход: одна строка вместо строки на лист. */
class CalcLogSummaryTest {

    private final LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
    private final Logger calcLog = ctx.getLogger("tj.mintrans.epd.waybill.calc.TariffMath");
    private final Logger otherLog = ctx.getLogger("tj.mintrans.epd.waybill.service.Other");
    private final Logger root = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attach() {
        CalcLogSummary.install();
        appender = new ListAppender<>();
        appender.setContext(ctx);
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void detach() {
        root.detachAppender(appender);
    }

    private List<ILoggingEvent> warns() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    @Test
    @DisplayName("вне прохода WARN расчёта пишется как обычно")
    void outsideScopeUnchanged() {
        calcLog.warn("Стоимость пробега: price_per_1_mkm не заполнен, применён 0");

        assertThat(warns()).hasSize(1);
        assertThat(warns().getFirst().getFormattedMessage()).startsWith("Стоимость пробега");
    }

    @Test
    @DisplayName("в проходе 1000 листов × 3 WARN -> одна итоговая строка с количеством по видам")
    void insideScopeAggregated() {
        try (CalcLogSummary.Scope s = CalcLogSummary.open("2026-07-01 — 2026-07-31 (все организации)")) {
            for (int i = 0; i < 1000; i++) {
                calcLog.warn("Стоимость пробега: price_per_1_mkm не заполнен, применён 0");
                calcLog.warn("Стоимость поездок: price_one_time не заполнен, применён 0");
                if (i % 2 == 0) {
                    calcLog.warn("Коэффициент износа: год выпуска ТС не заполнен, применено значение {}", 0);
                }
                s.item();
            }
            assertThat(warns()).isEmpty();
        }

        List<ILoggingEvent> w = warns();
        assertThat(w).hasSize(1);
        String line = w.getFirst().getFormattedMessage();
        assertThat(line).contains("листов 1000", "незаполненных данных 2500",
                "«Стоимость пробега: price_per_1_mkm не заполнен, применён 0» × 1000",
                "год выпуска ТС не заполнен, применено значение {}» × 500");
        assertThat(CalcLogSummary.current()).isNull();
    }

    @Test
    @DisplayName("WARN других пакетов в проходе не подавляются; вложенный проход не пишет свой итог")
    void otherPackagesAndNesting() {
        try (CalcLogSummary.Scope outer = CalcLogSummary.open("outer")) {
            try (CalcLogSummary.Scope inner = CalcLogSummary.open("inner")) {
                calcLog.warn("Заработок водителя: percent_income не заполнен, применён 0");
                otherLog.warn("настоящая проблема");
            }
            assertThat(CalcLogSummary.current()).isNotNull();
            assertThat(warns()).extracting(ILoggingEvent::getFormattedMessage).containsExactly("настоящая проблема");
        }
        assertThat(warns()).hasSize(2);
        assertThat(warns().get(1).getFormattedMessage()).startsWith("Отчётный проход outer");
    }
}
