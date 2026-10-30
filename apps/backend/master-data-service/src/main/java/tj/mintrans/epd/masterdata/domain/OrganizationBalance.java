package tj.mintrans.epd.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Текущий баланс организации по биллингу выдачи ПЛ (решение владельца 31.10.2026). Пост-оплата:
 * баланс может уйти в минус — списание при выдаче (Т1) не блокирует документ, пополнение вносит
 * SYSTEM_ADMIN по факту внешнего банковского перевода.
 */
@Entity
@Table(name = "organization_balance")
public class OrganizationBalance {

    @Id
    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "balance_tjs", nullable = false)
    private BigDecimal balanceTjs = BigDecimal.ZERO;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }
    public BigDecimal getBalanceTjs() { return balanceTjs; }
    public void setBalanceTjs(BigDecimal balanceTjs) { this.balanceTjs = balanceTjs; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
