package tj.mintrans.epd.waybill.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.waybill.domain.FuelRecord;

import java.util.List;
import java.util.UUID;

public interface FuelRecordRepository extends JpaRepository<FuelRecord, UUID> {

    List<FuelRecord> findByWaybillIdOrderByCreatedAt(UUID waybillId);
}
