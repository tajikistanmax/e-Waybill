package tj.mintrans.epd.waybill.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.client.MasterDataClient;
import tj.mintrans.epd.waybill.config.CurrentUser;
import tj.mintrans.epd.waybill.domain.Notification;
import tj.mintrans.epd.waybill.event.WaybillStatusChanged;
import tj.mintrans.epd.waybill.notify.NotificationChannel;
import tj.mintrans.epd.waybill.repository.NotificationRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Уведомления организации о событиях путевого листа. Генерация — после commit
 * из события WaybillStatusChanged (в отдельной транзакции; сбой не влияет на переход).
 */
@Service
public class NotificationService {

    /** Заголовок + роли-адресаты уведомления по статусу-источнику. */
    private record NotifyDef(String title, String targetRoles) {
    }

    /**
     * Статусы, о которых уведомляем, с адресацией по роли. Большинство типов — сигналы
     * «диспетчеру/админу компании нужно действовать» (переоформить, выдать, отреагировать
     * на блокировку/просрочку) — бухгалтеру и водителю они не по работе. Врачу и механику
     * прежде не заводилось ни одного специфичного типа вовсе (найдено УАТ 2026-09-04:
     * оба видели один и тот же поток из-за исторического fallback «видно всем» — не потому,
     * что им действительно нужны диспетчерские события). CREATED — единственное событие,
     * которое по смыслу принадлежит именно им: новый ПЛ поступил в их очередь осмотра
     * (тот самый список, который они видят в /med, /tech). Прочих типов «специально для
     * врача/механика» пока не заводим — остальное действительно вне их работы.
     */
    private static final Map<String, NotifyDef> NOTEWORTHY = Map.of(
            "CREATED", new NotifyDef("Путевой лист ожидает осмотра", "DOCTOR,MECHANIC"),
            "MED_REJECTED", new NotifyDef("Водитель не допущен по медосмотру", "DISPATCHER,COMPANY_ADMIN,BRANCH_ADMIN"),
            "TECH_REJECTED", new NotifyDef("ТС не прошло технический контроль", "DISPATCHER,COMPANY_ADMIN,BRANCH_ADMIN"),
            "READY", new NotifyDef("Путевой лист готов к выдаче", "DISPATCHER,COMPANY_ADMIN,BRANCH_ADMIN"),
            "BLOCKED", new NotifyDef("Путевой лист заблокирован инспектором", "DISPATCHER,COMPANY_ADMIN,BRANCH_ADMIN"),
            "EXPIRED", new NotifyDef("Путевой лист просрочен", "DISPATCHER,COMPANY_ADMIN,BRANCH_ADMIN"));

    private final NotificationRepository repository;
    private final CurrentUser currentUser;
    private final tj.mintrans.epd.waybill.config.TenantScope tenantScope;
    private final List<NotificationChannel> channels;
    private final MasterDataClient masterData;

    /** Кэш тумблеров уведомлений (настройки меняются редко; не бьём master-data на каждое событие). */
    private volatile Map<String, String> togglesCache = Map.of();
    private volatile long togglesCachedAt = 0;
    private static final long TOGGLES_TTL_MS = 30_000;

    public NotificationService(NotificationRepository repository, CurrentUser currentUser,
                               tj.mintrans.epd.waybill.config.TenantScope tenantScope,
                               List<NotificationChannel> channels, MasterDataClient masterData) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.tenantScope = tenantScope;
        this.channels = channels;
        this.masterData = masterData;
    }

    /** Создать уведомление по событию перехода (новая транзакция; best-effort). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onStatusChanged(WaybillStatusChanged event) {
        NotifyDef def = NOTEWORTHY.get(event.toStatus());
        if (def == null || event.organizationRma() == null) {
            return;
        }
        // Тумблер типа события (настройка notifications/notify_<status>): выключено админом → пропуск.
        // Отсутствие/недоступность настройки трактуется как «включено» (безопасный дефолт).
        if (!notificationEnabled(event.toStatus())) {
            return;
        }
        try {
            var n = new Notification();
            n.setRecipientRma(event.organizationRma());
            n.setWaybillId(event.waybillId());
            n.setKind(event.toStatus());
            n.setTitle(def.title());
            n.setTargetRoles(def.targetRoles());
            String number = event.number() != null ? event.number() : "б/н";
            String body = "Путевой лист № " + number;
            if (event.reason() != null && !event.reason().isBlank()) {
                body += ". " + event.reason();
            }
            n.setBody(body);
            repository.save(n);
            // Доставка во внешние каналы (SMS/push/…), если подключены. Каждый — best-effort.
            for (NotificationChannel channel : channels) {
                try { channel.send(n); } catch (RuntimeException ignored) { /* канал не должен рушить */ }
            }
        } catch (RuntimeException ex) {
            // Уведомление best-effort: сбой записи не должен влиять на бизнес-поток.
        }
    }

    /** Тип события включён к уведомлению? notify_<status>; отсутствие/недоступность → true (дефолт). */
    private boolean notificationEnabled(String status) {
        String key = "notify_" + status.toLowerCase();
        return !"false".equalsIgnoreCase(toggles().get(key));
    }

    /** Тумблеры уведомлений с кэшем на TTL (best-effort; при сбое — прежний кэш/пусто = «включено»). */
    private Map<String, String> toggles() {
        long now = System.currentTimeMillis();
        if (now - togglesCachedAt > TOGGLES_TTL_MS) {
            try {
                togglesCache = masterData.notificationSettings();
                togglesCachedAt = now;
            } catch (RuntimeException ignored) { /* оставляем прежний кэш */ }
        }
        return togglesCache;
    }

    // -------------------------------------------------------- чтение (тенант-скоуп)

    public List<Notification> list() {
        // Тенант видит уведомления своей организации (администратор компании — и её филиалов),
        // дополнительно отфильтрованные по своей роли (см. visibleForCurrentUser); платформенные
        // роли (админ/аналитик/инспектор/сервис) — все, без фильтра по роли (надзорный доступ).
        if (currentUser.isTenantScoped()) {
            var scope = tenantScope.rmas();
            if (scope.isEmpty() || scope.contains("__none__")) {
                return List.of();
            }
            return repository.findTop100ByRecipientRmaInOrderByCreatedAtDesc(scope).stream()
                    .filter(this::visibleForCurrentUser)
                    .toList();
        }
        return repository.findTop100ByOrderByCreatedAtDesc();
    }

    public long unreadCount() {
        if (currentUser.isTenantScoped()) {
            var scope = tenantScope.rmas();
            if (scope.isEmpty() || scope.contains("__none__")) {
                return 0L;
            }
            return repository.findByRecipientRmaInAndReadAtIsNull(scope).stream()
                    .filter(this::visibleForCurrentUser)
                    .count();
        }
        return repository.countByReadAtIsNull();
    }

    /**
     * target_roles пуст/не задан — видно всем ролям организации (обратная совместимость
     * старых записей и уведомлений без адресации). Иначе — только если у текущего
     * пользователя есть хотя бы одна из перечисленных ролей.
     */
    private boolean visibleForCurrentUser(Notification n) {
        String roles = n.getTargetRoles();
        if (roles == null || roles.isBlank()) {
            return true;
        }
        for (String role : roles.split(",")) {
            if (currentUser.hasRole(role.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Сообщение водителя диспетчерской («Сообщить о проблеме»): отдельный канал связи
     * «водитель → диспетчер», а не событие ПЛ. Создаёт уведомление для диспетчера/админа
     * своей организации (тот же поток, что видят в кабинете и в колокольчике).
     */
    @Transactional
    public void reportDriverIssue(String issueType, String message, UUID waybillId) {
        String org = currentUser.organizationRma()
                .orElseThrow(() -> new IllegalStateException("Организация водителя не определена в токене"));
        var n = new Notification();
        n.setRecipientRma(org);
        n.setWaybillId(waybillId);
        n.setKind("DRIVER_ISSUE");
        String type = (issueType == null || issueType.isBlank()) ? "Сообщение с рабочего места" : issueType.trim();
        n.setTitle(type);
        String who = currentUser.username().orElse("сотрудник");
        String body = (message != null && !message.isBlank() ? message.trim() + " " : "") + "(отправитель: " + who + ")";
        n.setBody(body);
        n.setTargetRoles("DISPATCHER,COMPANY_ADMIN,BRANCH_ADMIN");
        repository.save(n);
        for (NotificationChannel channel : channels) {
            try { channel.send(n); } catch (RuntimeException ignored) { /* канал не должен рушить */ }
        }
    }

    @Transactional
    public void markRead(UUID id) {
        repository.findById(id).ifPresent(n -> {
            boolean own = !currentUser.isTenantScoped() || tenantScope.contains(n.getRecipientRma());
            if (own && visibleForCurrentUser(n) && n.getReadAt() == null) {
                n.setReadAt(OffsetDateTime.now());
                repository.save(n);
            }
        });
    }

    @Transactional
    public void markAllRead() {
        if (currentUser.isTenantScoped()) {
            var scope = tenantScope.rmas();
            if (scope.isEmpty() || scope.contains("__none__")) {
                return;
            }
            repository.findByRecipientRmaInAndReadAtIsNull(scope).stream()
                    .filter(this::visibleForCurrentUser)
                    .forEach(n -> {
                        n.setReadAt(OffsetDateTime.now());
                        repository.save(n);
                    });
            return;
        }
        currentUser.organizationRma().ifPresent(rma ->
                repository.findByRecipientRmaAndReadAtIsNull(rma).forEach(n -> {
                    n.setReadAt(OffsetDateTime.now());
                    repository.save(n);
                }));
    }
}
