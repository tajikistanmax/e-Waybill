package tj.mintrans.epd.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Доступ роли к разделам меню (UI-навигация, §29). НЕ граница безопасности — реальные права
 * проверяет @PreAuthorize по ролям Keycloak. home_key — стартовый раздел роли; nav_keys — CSV
 * разделов бокового меню. Редактирует SYSTEM_ADMIN на /settings/roles.
 */
@Entity
@Table(name = "role_access")
public class RoleAccess {

    @Id
    private String role;

    @Column(name = "home_key", nullable = false)
    private String homeKey;

    /** CSV ключей разделов меню (dashboard,waybills,…). */
    @Column(name = "nav_keys", nullable = false)
    private String navKeys;

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = OffsetDateTime.now();
    }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getHomeKey() { return homeKey; }
    public void setHomeKey(String homeKey) { this.homeKey = homeKey; }
    public String getNavKeys() { return navKeys; }
    public void setNavKeys(String navKeys) { this.navKeys = navKeys; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
