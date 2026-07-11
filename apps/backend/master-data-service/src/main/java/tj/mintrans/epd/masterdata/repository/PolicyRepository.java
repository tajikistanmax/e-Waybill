package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.Policy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyRepository extends JpaRepository<Policy, UUID> {

    List<Policy> findByEnabledTrue();

    Optional<Policy> findByScopeLevelAndScopeKeyAndRuleKey(String scopeLevel, String scopeKey, String ruleKey);
}
