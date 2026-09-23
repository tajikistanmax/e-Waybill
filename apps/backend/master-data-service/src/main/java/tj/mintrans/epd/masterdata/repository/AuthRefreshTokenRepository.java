package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tj.mintrans.epd.masterdata.domain.AuthRefreshToken;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface AuthRefreshTokenRepository extends JpaRepository<AuthRefreshToken, UUID> {

    Optional<AuthRefreshToken> findByTokenHash(String tokenHash);

    /** Отозвать все токены пользователя — при смене пароля, блокировке и удалении учётки. */
    @Modifying
    @Query("update AuthRefreshToken t set t.revoked = true where t.userId = :userId and t.revoked = false")
    int revokeAllForUser(@Param("userId") UUID userId);

    /** Уборка просроченных записей (их уже нельзя предъявить). */
    @Modifying
    @Query("delete from AuthRefreshToken t where t.expiresAt < :before")
    int deleteExpired(@Param("before") OffsetDateTime before);
}
