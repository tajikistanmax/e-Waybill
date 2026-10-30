package tj.mintrans.epd.masterdata.service;

import org.springframework.stereotype.Service;
import tj.mintrans.epd.masterdata.domain.WaybillFeeTariff;
import tj.mintrans.epd.masterdata.repository.WaybillFeeTariffRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Ставки платы за выдачу путевого листа (решение владельца 31.10.2026). В отличие от движка
 * политик (Policy), переопределение по организации здесь задаёт только SYSTEM_ADMIN — тариф
 * не самообслуживание, см. {@code tj.mintrans.epd.masterdata.web.WaybillFeeTariffController}.
 */
@Service
public class WaybillFeeTariffService {

    private final WaybillFeeTariffRepository tariffs;

    public WaybillFeeTariffService(WaybillFeeTariffRepository tariffs) {
        this.tariffs = tariffs;
    }

    /** Все ставки (национальные и переопределения) — для экрана настроек тарифов. */
    public List<WaybillFeeTariff> list() {
        return tariffs.findAll();
    }

    /**
     * Эффективная ставка для (тип ПЛ, организация): переопределение организации, если задано,
     * иначе национальная ставка, иначе {@code BigDecimal.ZERO} (тариф не настроен — выдача
     * бесплатна, как и прочие правила этой платформы по умолчанию выключены до настройки).
     */
    public BigDecimal effectiveFee(String waybillType, UUID organizationId) {
        if (organizationId != null) {
            var org = tariffs.findByWaybillTypeAndOrganizationId(waybillType, organizationId);
            if (org.isPresent()) {
                return org.get().getAmountTjs();
            }
        }
        return tariffs.findByWaybillTypeAndOrganizationIdIsNull(waybillType)
                .map(WaybillFeeTariff::getAmountTjs)
                .orElse(BigDecimal.ZERO);
    }

    public WaybillFeeTariff upsertNational(String waybillType, BigDecimal amount, String updatedBy) {
        var t = tariffs.findByWaybillTypeAndOrganizationIdIsNull(waybillType).orElseGet(WaybillFeeTariff::new);
        t.setWaybillType(waybillType);
        t.setOrganizationId(null);
        t.setAmountTjs(amount);
        t.setUpdatedBy(updatedBy);
        t.setUpdatedAt(OffsetDateTime.now());
        return tariffs.save(t);
    }

    public WaybillFeeTariff upsertOrganization(String waybillType, UUID organizationId, BigDecimal amount, String updatedBy) {
        var t = tariffs.findByWaybillTypeAndOrganizationId(waybillType, organizationId).orElseGet(WaybillFeeTariff::new);
        t.setWaybillType(waybillType);
        t.setOrganizationId(organizationId);
        t.setAmountTjs(amount);
        t.setUpdatedBy(updatedBy);
        t.setUpdatedAt(OffsetDateTime.now());
        return tariffs.save(t);
    }

    public void delete(UUID id) {
        tariffs.deleteById(id);
    }
}
