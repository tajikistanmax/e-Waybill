package tj.mintrans.epd.waybill.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillTitle;
import tj.mintrans.epd.waybill.repository.WaybillTitleRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Журналы предрейсового контроля — перенос отчётов ИС «Роҳхат»
 * «Дафтари қайди механик» (тип 13) и «Дафтари қайди духтӯр» (тип 14):
 * хронологический список путевых листов за период с отметками и подписями
 * механика (титул Т3) либо врача (титулы Т2 предрейсовый / Т6 послерейсовый).
 *
 * <p>Мультиарендность — как в {@link WaybillReportService}: тенант видит только
 * свою организацию.</p>
 */
@Service
public class InspectionJournalService {

    /** Служебные ключи в data титула — не показываем как показатели осмотра.
     * indicatorsEnc (Т2/Т6) — зашифрованный blob медпоказателей (ИБ-13.1.3, MedicalDataCrypto),
     * его тоже незачем показывать как «деталь» — это не читаемые данные, а ciphertext. */
    private static final Set<String> META = Set.of("verdict", "employeeName", "employeeRma", "dispatcher", "indicatorsEnc");

    private final WaybillPeriodScan scan;
    private final WaybillTitleRepository titles;
    private final tj.mintrans.epd.waybill.config.TenantScope tenantScope;

    public InspectionJournalService(WaybillPeriodScan scan, WaybillTitleRepository titles,
                                    tj.mintrans.epd.waybill.config.TenantScope tenantScope) {
        this.scan = scan;
        this.titles = titles;
        this.tenantScope = tenantScope;
    }

    public record Mark(String verdict, String employeeName, String employeeRma,
                       String signedAt, String fingerprint, String details) {
    }

    /**
     * Строка журнала механика. Сторона возврата — как в legacy «Дафтари қайди механик» (сверка 25.09, D13):
     * {@code exitAt}/{@code entryAt} — выезд (Т4) и возврат (Т5), {@code odometerEntry} — одометр возврата,
     * {@code entryCondition} — техсостояние при возврате (legacy «Коршоям», если лист возвращён после допуска).
     */
    public record MechanicRow(String number, String date, String vehicle, String driver,
                              Integer odometerExit, Mark control,
                              String exitAt, String entryAt, Integer odometerEntry, String entryCondition) {
    }

    public record DoctorRow(String number, String date, String vehicle, String driver,
                            Mark preTrip, Mark postTrip) {
    }

    public record MechanicJournal(LocalDate from, LocalDate to, String organizationRma,
                                  List<MechanicRow> rows) {
    }

    public record DoctorJournal(LocalDate from, LocalDate to, String organizationRma,
                                List<DoctorRow> rows) {
    }

    @Transactional(readOnly = true)
    public MechanicJournal mechanic(LocalDate from, LocalDate to, String requestedOrg) {
        Set<String> scope = resolveScope(requestedOrg);
        String org = scope == null ? null : String.join(",", scope);
        List<MechanicRow> rows = new ArrayList<>();
        // Потоком по периоду в порядке created_at (WaybillPeriodScan), а не findAll() — Ф5: 2,3 млн ПЛ.
        scan.forEach(from, to, scope, wb -> {
            List<WaybillTitle> ts = titles.findByWaybillIdOrderBySignedAt(wb.getId());
            Mark control = mark(find(ts, "T3"));
            if (control == null) {
                return;
            }
            WaybillTitle exit = find(ts, "T4");
            WaybillTitle entry = find(ts, "T5");
            boolean passed = wb.isTechPassed();
            rows.add(new MechanicRow(number(wb), date(wb), vehicle(wb), driver(wb),
                    wb.getOdometerExit(), control,
                    exit == null || exit.getSignedAt() == null ? null : exit.getSignedAt().toString(),
                    entry == null || entry.getSignedAt() == null ? null : entry.getSignedAt().toString(),
                    wb.getOdometerEntry(),
                    entry != null && passed ? "исправен (Коршоям)" : null));
        });
        return new MechanicJournal(from, to, org, rows);
    }

    @Transactional(readOnly = true)
    public DoctorJournal doctor(LocalDate from, LocalDate to, String requestedOrg) {
        Set<String> scope = resolveScope(requestedOrg);
        String org = scope == null ? null : String.join(",", scope);
        List<DoctorRow> rows = new ArrayList<>();
        scan.forEach(from, to, scope, wb -> {
            List<WaybillTitle> ts = titles.findByWaybillIdOrderBySignedAt(wb.getId());
            Mark pre = mark(find(ts, "T2"));
            Mark post = mark(find(ts, "T6"));
            if (pre == null && post == null) {
                return;
            }
            rows.add(new DoctorRow(number(wb), date(wb), vehicle(wb), driver(wb), pre, post));
        });
        return new DoctorJournal(from, to, org, rows);
    }

    // ------------------------------------------------------------------

    private static WaybillTitle find(List<WaybillTitle> titles, String type) {
        return titles.stream().filter(t -> type.equals(t.getTitleType())).reduce((a, b) -> b).orElse(null);
    }

    /** Титулы медосмотра — их «детали» никогда не показываем построчно (см. ниже). */
    private static final Set<String> MEDICAL_TITLE_TYPES = Set.of("T2", "T6");

    private static Mark mark(WaybillTitle t) {
        if (t == null) {
            return null;
        }
        Map<String, Object> d = t.getData() == null ? Map.of() : t.getData();
        String verdict = str(d.get("verdict"));
        String name = str(d.get("employeeName"));
        if (name.isBlank()) {
            name = t.getSignerRma();
        }
        StringBuilder details = new StringBuilder();
        // Т2/Т6 (медосмотр) — особая категория ПДн (ИБ-13.1.2/13.7.2): в «деталях» журнала
        // НИКОГДА не показываем ничего, кроме вердикта, вне зависимости от того, что лежит
        // в data титула. Это защищает не только от текущего indicatorsEnc (шифрованный
        // blob и так безобиден в выводе), но и от ИСТОРИЧЕСКИХ записей, созданных ДО ввода
        // шифрования — у них в data лежат сырые pulse/alcotest/pressure открытым текстом,
        // и денай-лист META (белый список исключений, а не полей "можно показывать")
        // пропускал бы их как обычные "детали осмотра". Список META оставлен для Т3
        // (чек-лист техосмотра — не особая категория, показывать безопасно).
        if (!MEDICAL_TITLE_TYPES.contains(t.getTitleType())) {
            d.forEach((k, v) -> {
                if (!META.contains(k) && v != null && !v.toString().isBlank()) {
                    if (details.length() > 0) {
                        details.append("; ");
                    }
                    details.append(k).append('=').append(v);
                }
            });
        }
        return new Mark(verdict.isBlank() ? "—" : verdict, name, t.getSignerRma(),
                t.getSignedAt() == null ? "—" : t.getSignedAt().toString(),
                fingerprint(t.getSignature()), details.toString());
    }

    private static String fingerprint(String signature) {
        if (signature == null || signature.isBlank()) {
            return "—";
        }
        String hex = signature.replaceAll("(?i)^(sha256|sha512|sha1|cades|stub)[:\\-]?", "")
                .replaceAll("[^A-Fa-f0-9]", "").toUpperCase();
        if (hex.isEmpty()) {
            return "—";
        }
        return hex.length() <= 12 ? hex : hex.substring(hex.length() - 12);
    }

    private static String number(Waybill wb) {
        return wb.getNumber() != null ? wb.getNumber() : wb.getId().toString();
    }

    private static String date(Waybill wb) {
        return wb.getCreatedAt() == null ? "—" : wb.getCreatedAt().toLocalDate().toString();
    }

    private static String vehicle(Waybill wb) {
        String brand = snap(wb.getVehicleSnapshot(), "brand");
        return (brand.isBlank() ? "" : brand + " · ") + str(wb.getVehicleRegNumber());
    }

    private static String driver(Waybill wb) {
        String name = snap(wb.getDriverSnapshot(), "fullName");
        return (name.isBlank() ? wb.getDriverRma() : name) + " · " + str(wb.getDriverRma());
    }

    private static String snap(Map<String, Object> snapshot, String field) {
        if (snapshot == null || snapshot.get(field) == null) {
            return "";
        }
        return snapshot.get(field).toString();
    }

    /** Набор организаций журнала: {@code null} — все; иначе область тенанта («__none__» → пусто) либо запрошенная. */
    private Set<String> resolveScope(String requested) {
        if (tenantScope.isBounded()) {
            Set<String> s = tenantScope.rmas();
            return s == null || s.contains("__none__") ? Set.of() : s;
        }
        return requested == null || requested.isBlank() ? null : Set.of(requested.trim());
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
