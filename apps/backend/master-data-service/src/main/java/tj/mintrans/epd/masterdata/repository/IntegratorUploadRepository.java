package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.IntegratorUpload;

import java.util.Optional;
import java.util.UUID;

public interface IntegratorUploadRepository extends JpaRepository<IntegratorUpload, UUID> {

    Optional<IntegratorUpload> findByFileName(String fileName);

    boolean existsByFileName(String fileName);
}
