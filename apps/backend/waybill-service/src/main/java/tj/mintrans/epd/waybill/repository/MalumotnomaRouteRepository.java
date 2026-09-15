package tj.mintrans.epd.waybill.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.waybill.domain.MalumotnomaRoute;

import java.util.List;
import java.util.UUID;

public interface MalumotnomaRouteRepository extends JpaRepository<MalumotnomaRoute, UUID> {

    List<MalumotnomaRoute> findByActiveTrueOrderByName();

    List<MalumotnomaRoute> findAllByOrderByName();
}
