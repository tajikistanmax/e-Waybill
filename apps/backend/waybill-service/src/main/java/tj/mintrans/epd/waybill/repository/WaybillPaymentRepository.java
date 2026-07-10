package tj.mintrans.epd.waybill.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.waybill.domain.WaybillPayment;

import java.util.Optional;
import java.util.UUID;

public interface WaybillPaymentRepository extends JpaRepository<WaybillPayment, UUID> {

    Optional<WaybillPayment> findByWaybillId(UUID waybillId);
}
