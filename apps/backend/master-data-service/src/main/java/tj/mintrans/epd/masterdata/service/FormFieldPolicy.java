package tj.mintrans.epd.masterdata.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tj.mintrans.epd.masterdata.repository.PlatformSettingRepository;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Поля форм, которыми администратор управляет из настроек без изменения кода (решение
 * владельца 24.09.2026: «если руководство скажет, что поле не нужно, — скрыть его в настройках;
 * там же делать поле обязательным или необязательным»).
 *
 * <p>Настройка — категория {@code forms}, ключ = имя формы (например {@code organization}),
 * значение — JSON {@code {"поле":"hidden|required|show"}}. Не упомянутое поле показывается и
 * необязательно — поведение по умолчанию до появления настройки.</p>
 *
 * <p>Системные поля (РМА/ИНН и название организации) закреплены: на них держатся поиск,
 * иерархия филиалов и связи с парком, поэтому скрыть их или сделать необязательными нельзя.
 * Обязательность проверяется здесь, на сервере, — скрытие в браузере не должно быть
 * единственной защитой.</p>
 */
@Component
public class FormFieldPolicy {

    public static final String CATEGORY = "forms";
    public static final String ORGANIZATION = "organization";
    public static final String DRIVER = "driver";
    public static final String VEHICLE = "vehicle";
    public static final String EMPLOYEE = "employee";

    public enum Mode { SHOW, REQUIRED, HIDDEN }

    /**
     * Поле формы. {@code locked} — системно-обязательное (всегда REQUIRED);
     * {@code canRequire=false} — поле, для которого «обязательно» не имеет смысла (флажок).
     */
    public record Field(String key, String label, boolean locked, boolean canRequire) {
        static Field field(String key, String label) {
            return new Field(key, label, false, true);
        }
    }

    private static final Map<String, List<Field>> FORMS = Map.of(
            ORGANIZATION, List.of(
                    new Field("rma", "РМА / ИНН", true, true),
                    new Field("name", "Название", true, true),
                    Field.field("kpp", "КПП"),
                    Field.field("internalNumber", "Рамзи корхона (внутренний код)"),
                    Field.field("typeCompany", "Тип компании"),
                    Field.field("regionId", "Регион"),
                    Field.field("cityName", "Город"),
                    Field.field("address", "Адрес"),
                    Field.field("phone", "Телефон"),
                    Field.field("email", "Email"),
                    Field.field("nameHead", "Руководитель"),
                    Field.field("bank", "Банк"),
                    Field.field("licenseFrom", "Лицензия с"),
                    Field.field("licenseTo", "Лицензия по"),
                    Field.field("carrierLicenseNumber", "№ лицензии перевозчика"),
                    Field.field("percentIncome", "Доля дохода компании"),
                    Field.field("cat1", "Надбавка 1 класс"),
                    Field.field("cat2", "Надбавка 2 класс"),
                    Field.field("cat3", "Надбавка 3 класс"),
                    Field.field("ownership", "Форма собственности"),
                    Field.field("registrationCertNumber", "№ свидетельства о регистрации"),
                    Field.field("extractNumber", "№ выписки"),
                    Field.field("vatCertNumber", "№ свидетельства НДС"),
                    Field.field("planPassVolume", "План: объём перевозок"),
                    Field.field("planPassTraffic", "План: пассажирооборот"),
                    Field.field("latitude", "Широта"),
                    Field.field("longitude", "Долгота"),
                    Field.field("mapPoints", "Отметка на карте"),
                    new Field("giveFuel", "Предприятие выдаёт топливо", false, false),
                    Field.field("allowedWaybillTypes", "Разрешённые типы ПЛ")),
            // Водитель: РМА и Ф.И.О. — ключ записи и подпись во всех документах.
            DRIVER, List.of(
                    new Field("rma", "РМА / ИНН", true, true),
                    new Field("fullName", "Ф.И.О.", true, true),
                    Field.field("tabNumber", "Табельный номер"),
                    Field.field("birthDate", "Дата рождения"),
                    Field.field("experienceYears", "Стаж вождения"),
                    Field.field("licenseNumber", "Номер ВУ"),
                    Field.field("licenseCategories", "Категории ВУ"),
                    Field.field("licenseValidTo", "ВУ действует до"),
                    Field.field("degree", "Классность"),
                    Field.field("medCertNumber", "Медсправка №"),
                    Field.field("medCertValidTo", "Медсправка до"),
                    Field.field("medRestrictions", "Медограничения"),
                    Field.field("safetyCourseValidTo", "Курс БДД до"),
                    Field.field("safetyCourseNumber", "№ талона курса БДД"),
                    Field.field("adrCertValidTo", "Свидетельство ADR до"),
                    Field.field("phone", "Телефон"),
                    Field.field("passport", "Паспорт"),
                    Field.field("address", "Адрес"),
                    Field.field("email", "Email"),
                    Field.field("powerAttorney", "Доверенность"),
                    Field.field("visaValidTo", "Виза действует до"),
                    Field.field("contractNumber", "№ договора"),
                    Field.field("contractValidTo", "Договор действует до"),
                    // Закрепление ТС делается и из карточки ТС; обязательным его не делаем —
                    // в разделе «Парк» у перевозчика нет списка ТС в форме водителя.
                    new Field("assignedVehicleId", "Закреплённое ТС", false, false)),
            // ТС: госномер — ключ записи, тип — от него зависят виды ПЛ и формат номера.
            VEHICLE, List.of(
                    new Field("registrationNumber", "Госномер", true, true),
                    new Field("transportType", "Тип ТС", true, true),
                    Field.field("brand", "Марка / модель"),
                    Field.field("vincode", "VIN"),
                    Field.field("fuelType", "Вид топлива"),
                    Field.field("enginePower", "Мощность"),
                    Field.field("yearManufacture", "Год выпуска"),
                    Field.field("parkingNumber", "Стоянка"),
                    Field.field("capacity", "Вместимость"),
                    Field.field("carrying", "Грузоподъёмность"),
                    Field.field("odometer", "Одометр"),
                    Field.field("techInspectionValidTo", "Техосмотр до"),
                    Field.field("techInspectionNumber", "№ техосмотра"),
                    Field.field("techPassportNumber", "№ техпаспорта"),
                    Field.field("certificateNumber", "№ сертификата"),
                    Field.field("controlCardValidTo", "Контрольная карточка до"),
                    Field.field("controlCardNumber", "№ контрольного листа"),
                    Field.field("intlCertificateNumber", "№ сертификата ТС (межд.)"),
                    Field.field("intlControlCardNumber", "№ контр. листа (межд.)"),
                    Field.field("intlControlCardValidTo", "Контр. лист до (межд.)"),
                    Field.field("insuranceValidTo", "Страховка до"),
                    Field.field("adrApprovalValidTo", "Допуск ДОПОГ до"),
                    Field.field("airConditioner", "Кондиционер"),
                    Field.field("trailer1Number", "Прицеп 1 — госномер"),
                    Field.field("trailer1Brand", "Прицеп 1 — марка"),
                    Field.field("trailer1Carrying", "Прицеп 1 — грузоподъёмность"),
                    Field.field("trailer1Weight", "Прицеп 1 — вес"),
                    Field.field("trailer2Number", "Прицеп 2 — госномер"),
                    Field.field("trailer2Brand", "Прицеп 2 — марка"),
                    Field.field("trailer2Carrying", "Прицеп 2 — грузоподъёмность"),
                    Field.field("trailer2Weight", "Прицеп 2 — вес")),
            // Сотрудник: РМА, Ф.И.О. и должность — по должности выдаётся роль кабинета.
            EMPLOYEE, List.of(
                    new Field("rma", "РМА / ИНН", true, true),
                    new Field("name", "Ф.И.О.", true, true),
                    new Field("type", "Должность", true, true),
                    Field.field("tabNumber", "Табельный номер"),
                    Field.field("phone", "Телефон"),
                    Field.field("address", "Адрес"),
                    Field.field("certNumber", "Сертификат №"),
                    Field.field("certValidTo", "Сертификат действует до")));

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PlatformSettingRepository settings;

    public FormFieldPolicy(PlatformSettingRepository settings) {
        this.settings = settings;
    }

    /** Поля формы в порядке показа; неизвестная форма — пустой список. */
    public static List<Field> fields(String form) {
        return FORMS.getOrDefault(form, List.of());
    }

    public static boolean isKnownForm(String form) {
        return FORMS.containsKey(form);
    }

    /**
     * Действующие режимы полей формы. Чтение снисходительное: испорченная или устаревшая
     * настройка (неизвестный ключ, неверный режим) не ломает сохранение карточки — такие
     * элементы пропускаются. Закреплённые поля всегда REQUIRED.
     */
    public Map<String, Mode> modes(String form) {
        Map<String, Mode> out = new LinkedHashMap<>();
        for (Field f : fields(form)) {
            out.put(f.key(), f.locked() ? Mode.REQUIRED : Mode.SHOW);
        }
        String raw = settings.findByCategoryAndSettingKey(CATEGORY, form)
                .map(s -> s.getSettingValue()).orElse(null);
        if (raw == null || raw.isBlank()) {
            return out;
        }
        try {
            JsonNode root = JSON.readTree(raw);
            if (root == null || !root.isObject()) {
                return out;
            }
            for (Field f : fields(form)) {
                if (f.locked()) {
                    continue;
                }
                Mode m = parseMode(root.path(f.key()).asText(""));
                if (m == null || (m == Mode.REQUIRED && !f.canRequire())) {
                    continue;
                }
                out.put(f.key(), m);
            }
        } catch (Exception e) {
            // Строгая проверка — при сохранении настройки (validateSetting); здесь не падаем.
        }
        return out;
    }

    /**
     * Строгая проверка значения настройки при сохранении администратором: валидный JSON-объект,
     * известные поля, допустимые режимы; закреплённые поля нельзя скрыть или ослабить.
     */
    public void validateSetting(String form, String raw) {
        if (!isKnownForm(form)) {
            throw unprocessable("Неизвестная форма: " + form);
        }
        if (raw == null || raw.isBlank()) {
            return; // пусто — все поля по умолчанию
        }
        JsonNode root;
        try {
            root = JSON.readTree(raw);
        } catch (Exception e) {
            throw unprocessable("Значение должно быть JSON-объектом вида {\"поле\":\"hidden|required|show\"}");
        }
        if (root == null || !root.isObject()) {
            throw unprocessable("Значение должно быть JSON-объектом вида {\"поле\":\"hidden|required|show\"}");
        }
        Map<String, Field> byKey = new LinkedHashMap<>();
        for (Field f : fields(form)) {
            byKey.put(f.key(), f);
        }
        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
        while (it.hasNext()) {
            var e = it.next();
            Field f = byKey.get(e.getKey());
            if (f == null) {
                throw unprocessable("Неизвестное поле формы: " + e.getKey());
            }
            Mode m = e.getValue().isTextual() ? parseMode(e.getValue().asText()) : null;
            if (m == null) {
                throw unprocessable("Поле «%s»: режим должен быть hidden, required или show".formatted(f.label()));
            }
            if (f.locked() && m != Mode.REQUIRED) {
                throw unprocessable("Поле «%s» обязательно всегда: его нельзя скрыть или сделать необязательным"
                        .formatted(f.label()));
            }
            if (m == Mode.REQUIRED && !f.canRequire()) {
                throw unprocessable("Поле «%s» — отметка «да/нет», его нельзя сделать обязательным".formatted(f.label()));
            }
        }
    }

    /**
     * Проверка обязательных по настройке полей при сохранении карточки. Значение считается
     * пустым, если оно null или строка из пробелов. 422 со списком подписей незаполненных полей.
     */
    public void requireFilled(String form, Map<String, Object> values) {
        List<String> missing = new ArrayList<>();
        for (var e : modes(form).entrySet()) {
            if (e.getValue() != Mode.REQUIRED) {
                continue;
            }
            Object v = values.get(e.getKey());
            boolean empty = v == null || (v instanceof String s && s.isBlank());
            if (empty) {
                fields(form).stream().filter(f -> f.key().equals(e.getKey())).findFirst()
                        .ifPresent(f -> missing.add(f.label()));
            }
        }
        if (!missing.isEmpty()) {
            throw unprocessable("Заполните обязательные поля: " + String.join(", ", missing));
        }
    }

    /**
     * Проверка обязательных полей по телу запроса: значения берутся из компонентов record по
     * имени — имена полей формы совпадают с именами полей запроса, отдельный список не нужен.
     */
    public void requireFilled(String form, Record request) {
        requireFilled(form, valuesOf(request));
    }

    /** То же, но поля из {@code skip} не проверяются (поля единой платформы у её записей). */
    public void requireFilled(String form, Record request, java.util.Set<String> skip) {
        Map<String, Object> values = valuesOf(request);
        if (!skip.isEmpty()) {
            for (String k : skip) {
                values.put(k, "-"); // считаем заполненным: пользователь его не меняет
            }
        }
        requireFilled(form, values);
    }

    static Map<String, Object> valuesOf(Record rec) {
        Map<String, Object> out = new java.util.HashMap<>();
        for (var c : rec.getClass().getRecordComponents()) {
            try {
                out.put(c.getName(), c.getAccessor().invoke(rec));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Не прочитать поле " + c.getName(), e);
            }
        }
        return out;
    }

    private static Mode parseMode(String s) {
        return switch (s == null ? "" : s.trim().toLowerCase()) {
            case "hidden" -> Mode.HIDDEN;
            case "required" -> Mode.REQUIRED;
            case "show" -> Mode.SHOW;
            default -> null;
        };
    }

    private static ResponseStatusException unprocessable(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
