package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.Classifier;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClassifierRepository extends JpaRepository<Classifier, UUID> {

    List<Classifier> findByCategoryOrderBySortOrderAscCodeAsc(String category);

    List<Classifier> findByCategoryAndActiveTrueOrderBySortOrderAscCodeAsc(String category);

    Optional<Classifier> findByCategoryAndCode(String category, String code);
}
