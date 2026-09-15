package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.MobileDevice;

import java.util.List;
import java.util.UUID;

public interface MobileDeviceRepository extends JpaRepository<MobileDevice, UUID> {
    /** Все устройства (для платформенных ролей) — новые авторизации сверху. */
    List<MobileDevice> findAllByOrderByAuthorizedAtDesc();

    /** Устройства одной корхоны (мультиарендность: тенант видит только свою). */
    List<MobileDevice> findByOrganizationRmaOrderByAuthorizedAtDesc(String organizationRma);
}
