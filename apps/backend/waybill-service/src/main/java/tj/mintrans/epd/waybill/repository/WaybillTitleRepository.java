package tj.mintrans.epd.waybill.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tj.mintrans.epd.waybill.domain.WaybillTitle;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WaybillTitleRepository extends JpaRepository<WaybillTitle, UUID> {

    List<WaybillTitle> findByWaybillIdOrderBySignedAt(UUID waybillId);

    /**
     * Ровно один титул данного вида. ВНИМАНИЕ: титулы Т2 и Т6 у листа могут быть повторными
     * (повторный медосмотр после замены водителя, повторный послерейсовый осмотр) — тогда этот
     * метод бросает IncorrectResultSizeDataAccessException. В прикладном коде используйте
     * {@link #existsByWaybillIdAndTitleType} и {@link #findFirstByWaybillIdAndTitleTypeOrderBySignedAtDesc}.
     */
    Optional<WaybillTitle> findByWaybillIdAndTitleType(UUID waybillId, String titleType);

    boolean existsByWaybillIdAndTitleType(UUID waybillId, String titleType);

    /** Последний подписанный титул данного вида (Т2/Т6 могут повторяться). */
    Optional<WaybillTitle> findFirstByWaybillIdAndTitleTypeOrderBySignedAtDesc(UUID waybillId, String titleType);

    boolean existsByWaybillIdAndTitleTypeAndSignerRma(UUID waybillId, String titleType, String signerRma);

    /**
     * Титулы Т2/Т6, ещё хранящие сырые медпоказатели (созданы до включения шифрования,
     * ИБ-13.1.3) — у них нет ключа {@code indicatorsEnc} в data. Используется одноразовой
     * (идемпотентной) миграцией {@code MedicalDataMigration} при каждом старте сервиса.
     */
    // jsonb_exists(data, 'indicatorsEnc') вместо оператора `data ? 'indicatorsEnc'` — Hibernate
    // в native-запросах трактует одиночный `?` как JDBC-плейсхолдер позиционного параметра,
    // а не как JSONB-оператор "ключ существует", и падает на старте с "0 параметров присутствует".
    @Query(value = "SELECT * FROM waybill_title WHERE title_type IN ('T2','T6') "
            + "AND NOT jsonb_exists(data, 'indicatorsEnc')", nativeQuery = true)
    List<WaybillTitle> findLegacyUnencryptedMedicalTitles();
}
