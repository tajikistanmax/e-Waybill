package tj.mintrans.epd.waybill.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.waybill.domain.GpsPing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GpsPingRepository extends JpaRepository<GpsPing, UUID> {

    // Последняя позиция ТС по госномеру.
    Optional<GpsPing> findTop1ByVehicleRegNumberOrderByRecordedAtDesc(String vehicleRegNumber);

    // Трек по путевому листу (в хронологическом порядке, до 500 точек).
    List<GpsPing> findTop500ByWaybillIdOrderByRecordedAtAsc(UUID waybillId);
}
