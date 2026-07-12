package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.RoleAccess;

public interface RoleAccessRepository extends JpaRepository<RoleAccess, String> {
}
