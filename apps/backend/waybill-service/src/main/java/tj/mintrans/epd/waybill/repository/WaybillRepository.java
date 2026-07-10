package tj.mintrans.epd.waybill.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WaybillRepository extends JpaRepository<Waybill, UUID> {

    Optional<Waybill> findByNumber(String number);

    List<Waybill> findByVehicleRegNumberAndStatusIn(String vehicleRegNumber, Collection<WaybillStatus> statuses);

    List<Waybill> findByDriverRmaAndStatusIn(String driverRma, Collection<WaybillStatus> statuses);

    List<Waybill> findByOrganizationRmaOrderByCreatedAtDesc(String organizationRma);

    List<Waybill> findByStatusOrderByCreatedAtDesc(WaybillStatus status);
}
