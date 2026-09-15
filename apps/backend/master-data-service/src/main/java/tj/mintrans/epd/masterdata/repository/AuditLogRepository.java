package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tj.mintrans.epd.masterdata.domain.AuditLog;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    Page<AuditLog> findByEntityTypeOrderByOccurredAtDesc(String entityType, Pageable pageable);

    Page<AuditLog> findAllByOrderByOccurredAtDesc(Pageable pageable);

    /** Последняя запись цепочки хешей — seq монотонен и не подвержен коллизиям occurred_at. */
    Optional<AuditLog> findTopByOrderBySeqDesc();

    /** Весь журнал в порядке цепочки — для пересчёта/проверки целостности (V/GET /audit/verify). */
    Stream<AuditLog> findAllByOrderBySeqAsc();

    /** Записи, ещё не включённые в цепочку (историческая миграция при старте сервиса). */
    Stream<AuditLog> findByRecordHashIsNullOrderBySeqAsc();

    @Query(value = "SELECT pg_advisory_xact_lock(872634198)", nativeQuery = true)
    void acquireHashChainLock();
}
