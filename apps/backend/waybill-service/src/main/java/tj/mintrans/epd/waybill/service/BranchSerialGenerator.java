package tj.mintrans.epd.waybill.service;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Журнальный номер путевого листа в пределах организации (филиала) за календарный год —
 * аналог сквозной нумерации в бумажной книге учёта. Не связан с национальным номером
 * ({@link WaybillNumberGenerator}), который остаётся глобально уникальным.
 *
 * <p>Инкремент атомарен: {@code INSERT … ON CONFLICT DO UPDATE} блокирует строку счётчика,
 * конкурентные выдачи ПЛ одной организации сериализуются.</p>
 */
@Component
public class BranchSerialGenerator {

    private final EntityManager em;

    public BranchSerialGenerator(EntityManager em) {
        this.em = em;
    }

    @Transactional
    public int next(String organizationRma, int year) {
        em.createNativeQuery("""
                insert into waybill_org_counter (organization_rma, counter_year, counter)
                values (:org, :year, 1)
                on conflict (organization_rma, counter_year)
                do update set counter = waybill_org_counter.counter + 1
                """)
                .setParameter("org", organizationRma)
                .setParameter("year", year)
                .executeUpdate();
        Number counter = (Number) em.createNativeQuery("""
                select counter from waybill_org_counter
                where organization_rma = :org and counter_year = :year
                """)
                .setParameter("org", organizationRma)
                .setParameter("year", year)
                .getSingleResult();
        return counter.intValue();
    }
}
