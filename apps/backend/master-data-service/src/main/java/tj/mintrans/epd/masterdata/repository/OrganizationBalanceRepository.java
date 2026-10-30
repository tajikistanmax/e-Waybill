package tj.mintrans.epd.masterdata.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import tj.mintrans.epd.masterdata.domain.OrganizationBalance;

import java.util.Optional;
import java.util.UUID;

public interface OrganizationBalanceRepository extends JpaRepository<OrganizationBalance, UUID> {

    /**
     * Загрузка с блокировкой строки (SELECT … FOR UPDATE) — сериализует конкурентные
     * списания/пополнения одной организации (аналог Waybill.findByIdForUpdate): без неё
     * два одновременных списания при выдаче ПЛ теряли бы одно из обновлений баланса.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from OrganizationBalance b where b.organizationId = :organizationId")
    Optional<OrganizationBalance> findByIdForUpdate(UUID organizationId);
}
