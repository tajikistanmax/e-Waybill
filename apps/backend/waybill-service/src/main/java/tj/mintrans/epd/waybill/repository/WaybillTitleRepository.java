package tj.mintrans.epd.waybill.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.waybill.domain.WaybillTitle;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WaybillTitleRepository extends JpaRepository<WaybillTitle, UUID> {

    List<WaybillTitle> findByWaybillIdOrderBySignedAt(UUID waybillId);

    Optional<WaybillTitle> findByWaybillIdAndTitleType(UUID waybillId, String titleType);

    boolean existsByWaybillIdAndTitleTypeAndSignerRma(UUID waybillId, String titleType, String signerRma);
}
