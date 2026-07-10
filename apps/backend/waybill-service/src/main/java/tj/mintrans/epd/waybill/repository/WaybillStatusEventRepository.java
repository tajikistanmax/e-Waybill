package tj.mintrans.epd.waybill.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.waybill.domain.WaybillStatusEvent;

import java.util.List;
import java.util.UUID;

public interface WaybillStatusEventRepository extends JpaRepository<WaybillStatusEvent, UUID> {
    List<WaybillStatusEvent> findByWaybillIdOrderByCreatedAt(UUID waybillId);
}
