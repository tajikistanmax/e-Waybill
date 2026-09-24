package tj.mintrans.epd.masterdata.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tj.mintrans.epd.masterdata.repository.PlatformSettingRepository;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Кто ведёт справочники компаний, водителей, ТС и сотрудников (решение владельца 24.09.2026):
 * сама платформа («вручную», MANUAL — как было до сих пор) или единая платформа транспорта
 * e-Transport (UNIFIED), в которую e-Waybill встраивается модулем.
 *
 * <p>Настройка — категория {@code datasource}, ключ = форма ({@code organization}, {@code driver},
 * {@code vehicle}, {@code employee}), значение MANUAL|UNIFIED. Переключается в Настройки →
 * Интеграции в день подключения — без выпуска новой версии.</p>
 *
 * <p>В режиме UNIFIED для ручного ввода (всё, кроме push-канала API_INTEGRATOR):</p>
 * <ul>
 *   <li>новую запись завести нельзя — 409: записи приходят из единой платформы;</li>
 *   <li>у записи, пришедшей из единой платформы (source = UNIFIED), меняются только поля
 *       модуля путевых листов ({@link #MODULE_FIELDS}); остальные сервер оставляет как есть —
 *       их хозяин e-Transport, и двойной ввод развёл бы две базы;</li>
 *   <li>записи, заведённые у нас (перенос из старой системы, ручной ввод), правятся как раньше,
 *       пока единая платформа их не пришлёт.</li>
 * </ul>
 *
 * <p>Состав «полей модуля» — рабочее предположение до согласования перечня полей обмена с
 * командой e-Transport (вопрос к заказчику); правится здесь и в {@code formFields.ts}.</p>
 */
@Component
public class MasterDataSourcePolicy {

    public static final String CATEGORY = "datasource";
    public static final String UNIFIED = "UNIFIED";

    /** Поля, которые ведёт модуль путевых листов, даже если запись пришла из единой платформы. */
    static final Map<String, Set<String>> MODULE_FIELDS = Map.of(
            FormFieldPolicy.ORGANIZATION, Set.of("percentIncome", "cat1", "cat2", "cat3", "allowedWaybillTypes",
                    "giveFuel", "internalNumber", "mapPoints", "planPassVolume", "planPassTraffic"),
            FormFieldPolicy.DRIVER, Set.of("tabNumber", "degree", "assignedVehicleId"),
            FormFieldPolicy.VEHICLE, Set.of("parkingNumber", "airConditioner", "odometer"),
            FormFieldPolicy.EMPLOYEE, Set.of("tabNumber"));

    /** Дополнительно к полям формы защищаются: иерархия «компания → филиал» тоже из e-Transport. */
    private static final Map<String, Set<String>> EXTRA_OWNED = Map.of(
            FormFieldPolicy.ORGANIZATION, Set.of("parentRma"));

    private static final Map<String, String> NAMES = Map.of(
            FormFieldPolicy.ORGANIZATION, "Компании",
            FormFieldPolicy.DRIVER, "Водители",
            FormFieldPolicy.VEHICLE, "Транспортные средства",
            FormFieldPolicy.EMPLOYEE, "Сотрудники");

    private final PlatformSettingRepository settings;

    public MasterDataSourcePolicy(PlatformSettingRepository settings) {
        this.settings = settings;
    }

    /** Справочник ведёт единая платформа? Нет настройки / пусто / MANUAL — ведём сами. */
    public boolean unified(String form) {
        return settings.findByCategoryAndSettingKey(CATEGORY, form)
                .map(s -> UNIFIED.equalsIgnoreCase(String.valueOf(s.getSettingValue()).trim()))
                .orElse(false);
    }

    /**
     * Ручная запись (не API_INTEGRATOR). Возвращает запрос, который можно применять: как есть —
     * в режиме MANUAL или для записей, заведённых у нас; с полями e-Transport, заменёнными на
     * текущие значения, — для записи из единой платформы. Новую запись в режиме UNIFIED
     * отклоняет (409).
     *
     * @param existing текущая сущность или {@code null}, если записи ещё нет
     * @param existingSource её источник (MANUAL / UNIFIED)
     */
    public <R extends Record> R guardManualWrite(String form, R request, Object existing, String existingSource) {
        if (!unified(form)) {
            return request;
        }
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "%s ведутся в единой платформе транспорта (e-Transport): добавление вручную отключено "
                            .formatted(NAMES.getOrDefault(form, "Записи"))
                            + "(Настройки → Интеграции). Новая запись появится здесь, когда её заведут в единой платформе.");
        }
        if (!UNIFIED.equalsIgnoreCase(String.valueOf(existingSource))) {
            return request;
        }
        return keepOwnedFields(form, request, existing);
    }

    /**
     * Поля, которые не проверяются на обязательность при ручном сохранении: у записи из единой
     * платформы в режиме UNIFIED её поля пользователь изменить не может — требовать их
     * заполнения значило бы запретить и правку полей модуля.
     */
    public Set<String> skipRequired(String form, Object existing, String existingSource) {
        boolean locked = existing != null && UNIFIED.equalsIgnoreCase(String.valueOf(existingSource)) && unified(form);
        return locked ? ownedByUnified(form) : Set.of();
    }

    /** Поля, значения которых у записи из единой платформы сохраняются как есть. */
    static Set<String> ownedByUnified(String form) {
        Set<String> out = new HashSet<>();
        for (var f : FormFieldPolicy.fields(form)) {
            if (!MODULE_FIELDS.getOrDefault(form, Set.of()).contains(f.key())) {
                out.add(f.key());
            }
        }
        out.addAll(EXTRA_OWNED.getOrDefault(form, Set.of()));
        return out;
    }

    @SuppressWarnings("unchecked")
    static <R extends Record> R keepOwnedFields(String form, R request, Object existing) {
        Set<String> owned = ownedByUnified(form);
        RecordComponent[] comps = request.getClass().getRecordComponents();
        Object[] args = new Object[comps.length];
        Class<?>[] types = new Class<?>[comps.length];
        try {
            for (int i = 0; i < comps.length; i++) {
                var c = comps[i];
                types[i] = c.getType();
                Object value = c.getAccessor().invoke(request);
                if (owned.contains(c.getName())) {
                    Method getter = getter(existing.getClass(), c.getName());
                    value = getter.invoke(existing);
                }
                args[i] = value;
            }
            var ctor = request.getClass().getDeclaredConstructor(types);
            ctor.setAccessible(true);
            return (R) ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Не удалось сохранить поля единой платформы для " + form, e);
        }
    }

    /** Геттер свойства сущности: getX() или isX(). */
    static Method getter(Class<?> type, String property) throws NoSuchMethodException {
        String cap = Character.toUpperCase(property.charAt(0)) + property.substring(1);
        try {
            return type.getMethod("get" + cap);
        } catch (NoSuchMethodException e) {
            return type.getMethod("is" + cap);
        }
    }
}
