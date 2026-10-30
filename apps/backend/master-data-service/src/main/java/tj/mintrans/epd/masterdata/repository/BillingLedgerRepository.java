package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.BillingLedgerEntry;

import java.util.UUID;

public interface BillingLedgerRepository extends JpaRepository<BillingLedgerEntry, UUID> {

    boolean existsByWaybillId(UUID waybillId);

    Page<BillingLedgerEntry> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId, Pageable pageable);
}
