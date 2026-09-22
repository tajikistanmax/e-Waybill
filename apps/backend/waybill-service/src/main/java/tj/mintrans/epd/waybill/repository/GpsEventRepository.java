package tj.mintrans.epd.waybill.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import tj.mintrans.epd.waybill.domain.GpsEvent;
import tj.mintrans.epd.waybill.domain.GpsEventState;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface GpsEventRepository extends JpaRepository<GpsEvent, UUID>, JpaSpecificationExecutor<GpsEvent> {

    /** Последнее событие ТС того же состояния не раньше {@code since} (cooldown для состояний предприятия). */
    Optional<GpsEvent> findFirstByVehicleRegNumberAndStateAndEventTimeGreaterThanEqualOrderByEventTimeDesc(
            String vehicleRegNumber, GpsEventState state, OffsetDateTime since);

    /** То же с учётом направления (cooldown для маршрутных состояний). */
    Optional<GpsEvent> findFirstByVehicleRegNumberAndStateAndDirectionAndEventTimeGreaterThanEqualOrderByEventTimeDesc(
            String vehicleRegNumber, GpsEventState state, String direction, OffsetDateTime since);

    /** Последний въезд на маршрут по направлению (без ограничения датой — как в legacy). */
    Optional<GpsEvent> findFirstByVehicleRegNumberAndStateAndDirectionOrderByEventTimeDesc(
            String vehicleRegNumber, GpsEventState state, String direction);
}
