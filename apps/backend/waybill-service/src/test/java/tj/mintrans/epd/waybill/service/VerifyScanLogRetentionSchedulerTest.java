package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.Test;
import tj.mintrans.epd.waybill.repository.VerifyScanLogRepository;

import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ретенция verify_scan_log выключена по умолчанию (retentionDays=0) — ждёт
 * продуктового/юридического решения о сроке хранения (go-live чек-лист, п. 4.4).
 */
class VerifyScanLogRetentionSchedulerTest {

    private final VerifyScanLogRepository repository = mock(VerifyScanLogRepository.class);

    @Test
    void отключено_по_умолчанию_ничего_не_удаляет() {
        var scheduler = new VerifyScanLogRetentionScheduler(repository, 0);
        scheduler.run();
        verify(repository, never()).deleteByScannedAtBefore(any());
    }

    @Test
    void включено_удаляет_старше_порога() {
        when(repository.deleteByScannedAtBefore(any())).thenReturn(5L);
        var scheduler = new VerifyScanLogRetentionScheduler(repository, 30);
        scheduler.run();
        verify(repository).deleteByScannedAtBefore(any(OffsetDateTime.class));
    }
}
