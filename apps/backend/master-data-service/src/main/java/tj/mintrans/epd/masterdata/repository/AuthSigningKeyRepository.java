package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.AuthSigningKey;

import java.util.List;
import java.util.Optional;

public interface AuthSigningKeyRepository extends JpaRepository<AuthSigningKey, String> {

    Optional<AuthSigningKey> findFirstByActiveTrueOrderByCreatedAtDesc();

    /** Все ключи для набора проверки: после смены ключа токены со старым ещё живут до истечения. */
    List<AuthSigningKey> findAllByOrderByCreatedAtDesc();
}
