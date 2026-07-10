package tj.mintrans.epd.waybill.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.waybill.domain.WorkDay;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface WorkDayRepository extends JpaRepository<WorkDay, UUID> {

    List<WorkDay> findByWaybillIdOrderByWorkDate(UUID waybillId);

    boolean existsByWaybillIdAndWorkDate(UUID waybillId, LocalDate workDate);

    long countByWaybillId(UUID waybillId);
}
