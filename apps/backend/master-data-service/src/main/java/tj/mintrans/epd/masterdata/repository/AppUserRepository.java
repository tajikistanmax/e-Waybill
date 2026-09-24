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

    /**
     * Все учётные записи платформы для страницы «Пользователи» администратора (замена legacy
     * /admin/user). Пустая строка в параметре — фильтр не применяется. {@code q} — образец
     * «%текст%» в нижнем регистре; {@code role} — «%,РОЛЬ,%» (роли хранятся списком через
     * запятую, поэтому ищем целое слово, а не подстроку: ADMIN не должен находить COMPANY_ADMIN).
     */
    @Query("""
            select u from AppUser u
            where (:q = '' or lower(u.username) like :q or lower(coalesce(u.lastName, '')) like :q
                   or lower(coalesce(u.firstName, '')) like :q or coalesce(u.rma, '') like :q)
              and (:role = '' or concat(concat(',', u.roles), ',') like :role)
              and (:org = '' or u.organizationRma = :org)
              and (:withoutOrg = false or u.organizationRma is null)
              and (:status = ''
                   or (:status = 'ACTIVE' and u.enabled = true)
                   or (:status = 'BLOCKED' and u.enabled = false)
                   or (:status = 'LOCKED' and u.lockedUntil > :now))
            """)
    org.springframework.data.domain.Page<AppUser> search(@Param("q") String q, @Param("role") String role,
                                                         @Param("org") String org, @Param("withoutOrg") boolean withoutOrg,
                                                         @Param("status") String status,
                                                         @Param("now") java.time.OffsetDateTime now,
                                                         org.springframework.data.domain.Pageable pageable);
}
