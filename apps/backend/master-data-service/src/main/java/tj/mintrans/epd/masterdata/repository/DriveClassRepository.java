package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.DriveClass;

import java.util.Optional;

public interface DriveClassRepository extends JpaRepository<DriveClass, Long> {

    Optional<DriveClass> findFirstByDriveClassIgnoreCase(String driveClass);
}
