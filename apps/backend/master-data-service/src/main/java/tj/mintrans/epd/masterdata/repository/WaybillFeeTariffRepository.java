package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.WaybillFeeTariff;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WaybillFeeTariffRepository extends JpaRepository<WaybillFeeTariff, UUID> {

    List<WaybillFeeTariff> findAll();

    Optional<WaybillFeeTariff> findByWaybillTypeAndOrganizationIdIsNull(String waybillType);

    Optional<WaybillFeeTariff> findByWaybillTypeAndOrganizationId(String waybillType, UUID organizationId);
}
