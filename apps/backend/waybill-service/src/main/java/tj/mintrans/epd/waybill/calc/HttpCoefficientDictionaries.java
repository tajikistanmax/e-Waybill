package tj.mintrans.epd.waybill.calc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tj.mintrans.epd.waybill.calc.model.DriveClassRef;
import tj.mintrans.epd.waybill.calc.model.SimpleCoefRef;
import tj.mintrans.epd.waybill.calc.model.UsedCoefRef;
import tj.mintrans.epd.waybill.calc.model.WinterCoefRef;
import tj.mintrans.epd.waybill.client.MasterDataClient;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Реализация {@link CoefficientDictionaries} поверх {@link MasterDataClient} —
 * справочники коэффициентов читаются из master-data ({@code /api/v1/legacy-ref/*}).
 *
 * <p>Справочники меняются редко и малы по объёму, поэтому кэшируются на {@link #TTL}.
 * Недоступность master-data не роняет расчёт: возвращается пустой справочник, и
 * {@link CoefficientCalculator} применяет нейтральные значения / пороги оригинала.</p>
 */
@Component
public class HttpCoefficientDictionaries implements CoefficientDictionaries {

    private static final Logger log = LoggerFactory.getLogger(HttpCoefficientDictionaries.class);
    private static final Duration TTL = Duration.ofSeconds(60);

    private final MasterDataClient masterData;

    private volatile Snapshot snapshot;

    public HttpCoefficientDictionaries(MasterDataClient masterData) {
        this.masterData = masterData;
    }

    private record Snapshot(Instant loadedAt,
                            List<WinterCoefRef> winter,
                            List<SimpleCoefRef> mountain,
                            List<SimpleCoefRef> city,
                            List<UsedCoefRef> used,
                            List<DriveClassRef> driveClasses) {
    }

    private Snapshot current() {
        Snapshot cached = snapshot;
        if (cached != null && Duration.between(cached.loadedAt(), Instant.now()).compareTo(TTL) < 0) {
            return cached;
        }
        try {
            Snapshot fresh = new Snapshot(Instant.now(),
                    masterData.listWinterCoefs().stream().map(HttpCoefficientDictionaries::toWinter).toList(),
                    masterData.listMountainCoefs().stream().map(HttpCoefficientDictionaries::toSimple).toList(),
                    masterData.listCityCoefs().stream().map(HttpCoefficientDictionaries::toSimple).toList(),
                    masterData.listUsedCoefs().stream().map(HttpCoefficientDictionaries::toUsed).toList(),
                    masterData.listDriveClasses().stream().map(HttpCoefficientDictionaries::toDriveClass).toList());
            snapshot = fresh;
            return fresh;
        } catch (RuntimeException e) {
            log.warn("Справочники коэффициентов недоступны ({}); применён пустой набор", e.toString());
            if (cached != null) {
                return cached;
            }
            return new Snapshot(Instant.now(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    @Override
    public Optional<WinterCoefRef> winterCoef(long id) {
        return current().winter().stream().filter(w -> id == nz(w.id())).findFirst();
    }

    @Override
    public Optional<SimpleCoefRef> mountainCoef(long id) {
        return current().mountain().stream().filter(c -> id == nz(c.id())).findFirst();
    }

    @Override
    public Optional<SimpleCoefRef> cityCoef(long id) {
        return current().city().stream().filter(c -> id == nz(c.id())).findFirst();
    }

    @Override
    public List<UsedCoefRef> usedCoefRows() {
        return current().used();
    }

    @Override
    public List<DriveClassRef> driveClasses() {
        return current().driveClasses();
    }

    // ------------------------------------------------------------- разбор JSON-карт

    private static WinterCoefRef toWinter(Map<String, Object> m) {
        return new WinterCoefRef(asLong(m.get("id")), asDate(m.get("periodFrom")), asDate(m.get("periodTo")),
                asInt(m.get("coef")));
    }

    private static SimpleCoefRef toSimple(Map<String, Object> m) {
        return new SimpleCoefRef(asLong(m.get("id")), asInt(m.get("coef")));
    }

    private static UsedCoefRef toUsed(Map<String, Object> m) {
        Long km = asLong(m.get("km"));
        return new UsedCoefRef(asInt(m.get("year")), km, asInt(m.get("coef")));
    }

    private static DriveClassRef toDriveClass(Map<String, Object> m) {
        Object cls = m.get("driveClass");
        return new DriveClassRef(cls == null ? null : cls.toString(), asInt(m.get("coef")));
    }

    private static long nz(Long value) {
        return value == null ? Long.MIN_VALUE : value;
    }

    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number n ? n.longValue() : Long.valueOf(value.toString());
    }

    private static Integer asInt(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number n ? n.intValue() : Integer.valueOf(value.toString());
    }

    private static LocalDate asDate(Object value) {
        if (value == null) {
            return null;
        }
        return LocalDate.parse(value.toString());
    }
}
