package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.domain.WaybillStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Семантика «путевой лист отработан» для отчётности (блок F сквозной приёмки 22.09.2026).
 *
 * <p>Архив ставится планировщиком уже ПОСЛЕ закрытия листа (COMPLETED → ARCHIVED по сроку
 * ретенции), поэтому для истории перевозок это один и тот же факт выполненной работы. Пока
 * отчёты сверялись с одним {@link WaybillStatus#COMPLETED}, любой прошедший период показывал
 * нули: на стенде 2,28 млн перенесённых листов лежат в ARCHIVED, и сводка за июль 2026
 * выдавала «52 895 путевых листов, пробег 0».</p>
 */
class FinishedStatusesReportTest {

    @Test
    @DisplayName("отработанные листы: закрытый и архивный")
    void finishedCoversCompletedAndArchived() {
        assertThat(WaybillStatus.FINISHED)
                .containsExactlyInAnyOrder(WaybillStatus.COMPLETED, WaybillStatus.ARCHIVED);
    }

    @Test
    @DisplayName("незавершённые и отменённые в отчёт о выполненной работе не попадают")
    void unfinishedAndCancelledExcluded() {
        assertThat(WaybillStatus.FINISHED).doesNotContain(
                WaybillStatus.DRAFT, WaybillStatus.CREATED, WaybillStatus.READY,
                WaybillStatus.ISSUED, WaybillStatus.ACTIVE, WaybillStatus.RETURNED,
                WaybillStatus.CANCELLED, WaybillStatus.EXPIRED, WaybillStatus.BLOCKED);
    }

    @Test
    @DisplayName("возврат (Т5) ещё не отработанный лист: одометр возврата есть, но лист не закрыт")
    void returnedIsNotFinished() {
        assertThat(WaybillStatus.FINISHED).doesNotContain(WaybillStatus.RETURNED);
        assertThat(WaybillStatus.RETURNED.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("все отработанные статусы терминальны")
    void finishedAreTerminal() {
        assertThat(WaybillStatus.FINISHED).allMatch(WaybillStatus::isTerminal);
    }
}
