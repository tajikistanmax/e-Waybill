package tj.mintrans.epd.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Учётная запись платформы (V76). Заменяет пользователя Keycloak: те же поля попадают
 * в токен, поэтому проверка прав в обеих службах остаётся прежней.
 *
 * <p>Роли и контрагенты хранятся списком через запятую — так же, как они едут в токене;
 * отдельная таблица связей здесь избыточна: у пользователя одна роль плюс изредка список
 * контрагентов кабинета накладных.</p>
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    private String email;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    private String rma;

    @Column(name = "organization_rma")
    private String organizationRma;

    @Column(nullable = false)
    private String roles = "";

    @Column(name = "client_ids")
    private String clientIds;

    /**
     * Каналы внешней системы-интегратора через запятую (V86): ref, aggregator, gps, neru.
     * {@code null} — без ограничения (служебная учётка межсервисных вызовов).
     */
    @Column(name = "api_channels")
    private String apiChannels;

    @Column(name = "totp_secret")
    private String totpSecret;

    @Column(name = "totp_required", nullable = false)
    private boolean totpRequired;

    /** Секрет первой настройки, ещё не подтверждённый кодом (V81). */
    @Column(name = "totp_pending_secret")
    private String totpPendingSecret;

    /** Шаг времени последнего принятого кода — повтор кода не принимается (V81). */
    @Column(name = "totp_last_step")
    private Long totpLastStep;

    @Column(name = "totp_enrolled_at")
    private OffsetDateTime totpEnrolledAt;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private OffsetDateTime lockedUntil;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    /** Роли списком (пустые элементы отбрасываются). */
    public List<String> roleList() {
        return split(roles);
    }

    public void setRoleList(List<String> list) {
        this.roles = list == null ? "" : String.join(",", list);
    }

    /** Контрагенты кабинета накладных списком. */
    public List<String> clientIdList() {
        return split(clientIds);
    }

    public void setClientIdList(List<String> list) {
        this.clientIds = (list == null || list.isEmpty()) ? null : String.join(",", list);
    }

    /** Каналы интегратора списком; {@code null} — учётка без ограничения каналов. */
    public List<String> apiChannelList() {
        return apiChannels == null ? null : split(apiChannels);
    }

    public void setApiChannelList(List<String> list) {
        this.apiChannels = list == null ? null : String.join(",", list);
    }

    private static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String s : Arrays.asList(raw.split(","))) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** Полное имя для подписи и журнала: «Фамилия Имя», пустое — null. */
    public String fullName() {
        String composed = ((lastName == null ? "" : lastName) + " " + (firstName == null ? "" : firstName)).trim();
        return composed.isEmpty() ? null : composed;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    public String getRma() {
        return rma;
    }

    public void setRma(String rma) {
        this.rma = rma;
    }

    public String getOrganizationRma() {
        return organizationRma;
    }

    public void setOrganizationRma(String organizationRma) {
        this.organizationRma = organizationRma;
    }

    public String getRoles() {
        return roles;
    }

    public void setRoles(String roles) {
        this.roles = roles;
    }

    public String getClientIds() {
        return clientIds;
    }

    public void setClientIds(String clientIds) {
        this.clientIds = clientIds;
    }

    public String getApiChannels() {
        return apiChannels;
    }

    public String getTotpSecret() {
        return totpSecret;
    }

    public void setTotpSecret(String totpSecret) {
        this.totpSecret = totpSecret;
    }

    public boolean isTotpRequired() {
        return totpRequired;
    }

    public void setTotpRequired(boolean totpRequired) {
        this.totpRequired = totpRequired;
    }

    public String getTotpPendingSecret() {
        return totpPendingSecret;
    }

    public void setTotpPendingSecret(String totpPendingSecret) {
        this.totpPendingSecret = totpPendingSecret;
    }

    public Long getTotpLastStep() {
        return totpLastStep;
    }

    public void setTotpLastStep(Long totpLastStep) {
        this.totpLastStep = totpLastStep;
    }

    public OffsetDateTime getTotpEnrolledAt() {
        return totpEnrolledAt;
    }

    public void setTotpEnrolledAt(OffsetDateTime totpEnrolledAt) {
        this.totpEnrolledAt = totpEnrolledAt;
    }

    /** Второй фактор спрашивается: обязателен по роли либо уже подключён самим пользователем. */
    public boolean secondFactorApplies() {
        return totpRequired || totpSecret != null;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public void setFailedAttempts(int failedAttempts) {
        this.failedAttempts = failedAttempts;
    }

    public OffsetDateTime getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(OffsetDateTime lockedUntil) {
        this.lockedUntil = lockedUntil;
    }

    public OffsetDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(OffsetDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
