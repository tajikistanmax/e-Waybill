package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.Organization;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
    Optional<Organization> findByRma(String rma);

    /** Филиалы головной компании. */
    List<Organization> findByParentRma(String parentRma);

    List<Organization> findByRmaIn(Collection<String> rmas);
}
