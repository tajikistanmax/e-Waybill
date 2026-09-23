package tj.mintrans.epd.masterdata.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tj.mintrans.epd.masterdata.domain.AppUser;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /** Поиск по логину независимо от регистра — как уникальный индекс uq_app_user_username. */
    @Query("select u from AppUser u where lower(u.username) = lower(:username)")
    Optional<AppUser> findByUsername(@Param("username") String username);

    List<AppUser> findByOrganizationRmaIn(Collection<String> organizationRmas);

    List<AppUser> findByOrganizationRma(String organizationRma);

    /** Есть ли уже учётная запись с таким логином (для внятного 409 вместо ошибки индекса). */
    @Query("select count(u) > 0 from AppUser u where lower(u.username) = lower(:username)")
    boolean existsByUsername(@Param("username") String username);
}
