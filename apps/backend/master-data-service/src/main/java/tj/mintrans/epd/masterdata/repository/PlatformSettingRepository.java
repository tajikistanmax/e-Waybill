package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tj.mintrans.epd.masterdata.domain.PlatformSetting;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlatformSettingRepository extends JpaRepository<PlatformSetting, UUID> {

    List<PlatformSetting> findByCategoryOrderBySortOrderAscSettingKeyAsc(String category);

    List<PlatformSetting> findByCategoryInOrderByCategoryAscSortOrderAsc(List<String> categories);

    Optional<PlatformSetting> findByCategoryAndSettingKey(String category, String settingKey);
}
