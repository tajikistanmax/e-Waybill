package tj.mintrans.epd.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Токен обновления (V76). В базе лежит только отпечаток — по самой записи восстановить
 * токен нельзя, поэтому утечка таблицы не даёт войти от чужого имени.
 */
@Entity
@Table(name = "auth_refresh_token")
public class AuthRefreshToken {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    /**
     * Назначение ключа (V81): {@link #REFRESH} — токен обновления сессии, {@link #PASSWORD_CHANGE} —
     * смена временного пароля, {@link #SECOND_FACTOR} — второй шаг входа. Каждая точка входа
     * принимает только своё назначение.
     */
    @Column(nullable = false)
    private String purpose = REFRESH;

    public static final String REFRESH = "REFRESH";
    public static final String PASSWORD_CHANGE = "PASSWORD_CHANGE";
    public static final String SECOND_FACTOR = "SECOND_FACTOR";

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = OffsetDateTime.now();
    }

    public boolean isUsable() {
        return !revoked && expiresAt != null && expiresAt.isAfter(OffsetDateTime.now());
    }

    /** Ключ годен и выдан именно для этого назначения. */
    public boolean isUsableFor(String expectedPurpose) {
        return isUsable() && expectedPurpose.equals(purpose);
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public void setRevoked(boolean revoked) {
        this.revoked = revoked;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
