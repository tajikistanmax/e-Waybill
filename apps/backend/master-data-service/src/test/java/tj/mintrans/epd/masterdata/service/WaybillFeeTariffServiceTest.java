package tj.mintrans.epd.masterdata.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tj.mintrans.epd.masterdata.domain.WaybillFeeTariff;
import tj.mintrans.epd.masterdata.repository.WaybillFeeTariffRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Решение владельца 31.10.2026: тариф за выдачу ПЛ — переопределение по организации бьёт национальное. */
class WaybillFeeTariffServiceTest {

    private WaybillFeeTariffRepository repo;
    private WaybillFeeTariffService service;
    private final List<WaybillFeeTariff> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repo = mock(WaybillFeeTariffRepository.class);
        service = new WaybillFeeTariffService(repo);
    }

    @Test
    void noTariffConfigured_isFree() {
        when(repo.findByWaybillTypeAndOrganizationIdIsNull("WB_CAR")).thenReturn(Optional.empty());
        assertThat(service.effectiveFee("WB_CAR", UUID.randomUUID())).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void nationalDefaultAppliesWithoutOrgOverride() {
        var orgId = UUID.randomUUID();
        var national = tariff("WB_CAR", null, "40.00");
        when(repo.findByWaybillTypeAndOrganizationId("WB_CAR", orgId)).thenReturn(Optional.empty());
        when(repo.findByWaybillTypeAndOrganizationIdIsNull("WB_CAR")).thenReturn(Optional.of(national));
        assertThat(service.effectiveFee("WB_CAR", orgId)).isEqualByComparingTo("40.00");
    }

    @Test
    void organizationOverrideBeatsNational() {
        var orgId = UUID.randomUUID();
        var national = tariff("WB_CAR", null, "40.00");
        var org = tariff("WB_CAR", orgId, "15.00");
        when(repo.findByWaybillTypeAndOrganizationId("WB_CAR", orgId)).thenReturn(Optional.of(org));
        when(repo.findByWaybillTypeAndOrganizationIdIsNull("WB_CAR")).thenReturn(Optional.of(national));
        assertThat(service.effectiveFee("WB_CAR", orgId)).isEqualByComparingTo("15.00");
    }

    @Test
    void nullOrganizationId_usesNationalOnly() {
        var national = tariff("WB_TAXI", null, "25.00");
        when(repo.findByWaybillTypeAndOrganizationIdIsNull("WB_TAXI")).thenReturn(Optional.of(national));
        assertThat(service.effectiveFee("WB_TAXI", null)).isEqualByComparingTo("25.00");
    }

    private static WaybillFeeTariff tariff(String type, UUID orgId, String amount) {
        var t = new WaybillFeeTariff();
        t.setWaybillType(type);
        t.setOrganizationId(orgId);
        t.setAmountTjs(new BigDecimal(amount));
        return t;
    }
}
