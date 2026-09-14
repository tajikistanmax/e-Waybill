package tj.mintrans.epd.waybill.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.domain.WaybillTitle;
import tj.mintrans.epd.waybill.repository.WaybillTitleRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Разовая (но идемпотентная — безопасно гонять при каждом старте) миграция:
 * дошифровывает медицинские показатели в титулах Т2/Т6, сохранённые ДО включения
 * {@link MedicalDataCrypto} (2026-09-03) — до этой даты сырые pulse/alcotest/pressure
 * писались в {@code waybill_title.data} открытым текстом.
 *
 * <p>Найдена находкой УАТ-приёмки 2026-09-04: сырые показатели этих старых записей
 * утекали через журнал «Дафтари қайди духтӯр» (details) и через прямой
 * {@code GET /api/v1/waybills/{id}/titles} — оба канала доступны нескольким
 * тенант-скоуп ролям (диспетчер/бухгалтер/админ компании и т.п.), не только врачу.
 * Простое исправление вывода ({@code InspectionJournalService}) закрывает только
 * один из двух каналов; настоящее решение — убрать сырые данные из БД совсем.</p>
 */
@Component
public class MedicalDataMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MedicalDataMigration.class);

    private final WaybillTitleRepository titles;
    private final MedicalDataCrypto crypto;

    public MedicalDataMigration(WaybillTitleRepository titles, MedicalDataCrypto crypto) {
        this.titles = titles;
        this.crypto = crypto;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<WaybillTitle> legacy = titles.findLegacyUnencryptedMedicalTitles();
        if (legacy.isEmpty()) {
            log.info("MedicalDataMigration: незашифрованных исторических Т2/Т6 не найдено, миграция не требуется");
            return;
        }
        log.warn("MedicalDataMigration: найдено {} исторических титулов Т2/Т6 с незашифрованными "
                + "показателями — дошифровываю", legacy.size());
        int migrated = 0;
        for (WaybillTitle t : legacy) {
            Map<String, Object> old = t.getData();
            if (old == null) {
                continue;
            }
            Map<String, Object> indicators = new LinkedHashMap<>(old);
            Object verdict = indicators.remove("verdict");
            Object employeeName = indicators.remove("employeeName");
            var fresh = new LinkedHashMap<String, Object>();
            if (verdict != null) {
                fresh.put("verdict", verdict);
            }
            if (employeeName != null) {
                fresh.put("employeeName", employeeName);
            }
            if (!indicators.isEmpty()) {
                String enc = crypto.encryptToBase64(indicators);
                if (enc != null) {
                    fresh.put("indicatorsEnc", enc);
                }
            }
            t.setData(fresh);
            titles.save(t);
            migrated++;
        }
        log.warn("MedicalDataMigration: дошифровано {} титулов", migrated);
    }
}
