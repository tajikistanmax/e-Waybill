package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.FieldDefinition;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FieldDefinitionRepository extends JpaRepository<FieldDefinition, UUID> {

    List<FieldDefinition> findByWaybillTypeAndActiveTrueOrderBySortOrderAscFieldKeyAsc(String waybillType);

    List<FieldDefinition> findByWaybillTypeOrderBySortOrderAscFieldKeyAsc(String waybillType);

    Optional<FieldDefinition> findByWaybillTypeAndFieldKey(String waybillType, String fieldKey);
}
