package tj.mintrans.epd.masterdata.service;

import org.springframework.stereotype.Component;
import tj.mintrans.epd.masterdata.domain.Driver;
import tj.mintrans.epd.masterdata.repository.DriverRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Авто-присвоение табельного номера водителя — перенос legacy {@code DriverObserver}
 * (MIGRATION.md 11.7): при создании/обновлении без номера {@code number = max(number по компании) + 1}.
 *
 * <p>Отличия от оригинала (осознанно): (1) legacy при создании перезаписывал номер max+1 даже если
 * он был указан в форме — у нас явно заданный номер сохраняется; (2) legacy при обновлении с пустым
 * номером выдавал новый max+1 — у нас при отсутствии номера в запросе сохраняется прежний номер
 * водителя (иначе upsert без поля «терял» бы номер), а новый выдаётся только если номера ещё нет.
 * Нумерация — по организации водителя (компания/филиал, как {@code company_id} в оригинале);
 * учитываются только целочисленные номера, нечисловые (буквенные) игнорируются.
 * Гонка двух одновременных созданий даёт одинаковый номер — как и в оригинале (уникальности нет).</p>
 */
@Component
public class DriverTabNumbers {

    private final DriverRepository drivers;

    public DriverTabNumbers(DriverRepository drivers) {
        this.drivers = drivers;
    }

    /**
     * Табельный номер для сохранения: заданный в запросе → как есть; иначе прежний номер водителя;
     * иначе следующий по организации.
     */
    public String resolve(String requested, Optional<Driver> existing, UUID organizationId) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        String current = existing.map(Driver::getTabNumber).orElse(null);
        if (current != null && !current.isBlank()) {
            return current;
        }
        return next(organizationId);
    }

    /** Следующий табельный номер организации: max(числовых) + 1, при отсутствии — «1». */
    public String next(UUID organizationId) {
        long max = 0;
        for (String n : drivers.findTabNumbersByOrganization(organizationId)) {
            String t = n == null ? "" : n.trim();
            if (t.isEmpty() || t.length() > 9 || !t.chars().allMatch(Character::isDigit)) {
                continue;
            }
            max = Math.max(max, Long.parseLong(t));
        }
        return String.valueOf(max + 1);
    }
}
