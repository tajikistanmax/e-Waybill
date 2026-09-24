package tj.mintrans.epd.masterdata.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tj.mintrans.epd.masterdata.domain.Driver;
import tj.mintrans.epd.masterdata.domain.Employee;
import tj.mintrans.epd.masterdata.domain.Organization;
import tj.mintrans.epd.masterdata.domain.PlatformSetting;
import tj.mintrans.epd.masterdata.domain.Vehicle;
import tj.mintrans.epd.masterdata.repository.PlatformSettingRepository;
import tj.mintrans.epd.masterdata.web.DriverController;
import tj.mintrans.epd.masterdata.web.EmployeeController;
import tj.mintrans.epd.masterdata.web.OrganizationController;
import tj.mintrans.epd.masterdata.web.VehicleController;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Кто ведёт справочники: платформа (MANUAL) или единая платформа e-Transport (UNIFIED). */
class MasterDataSourcePolicyTest {

    private MasterDataSourcePolicy policy(String driverMode) {
        var repo = mock(PlatformSettingRepository.class);
        when(repo.findByCategoryAndSettingKey(eq("datasource"), anyString())).thenReturn(Optional.empty());
        var s = new PlatformSetting();
        s.setSettingValue(driverMode);
        when(repo.findByCategoryAndSettingKey("datasource", "driver")).thenReturn(Optional.of(s));
        return new MasterDataSourcePolicy(repo);
    }

    private static DriverController.DriverRequest driverReq(String fullName, String passport, String tab) {
        return new DriverController.DriverRequest("111111111", "025680800", tab, fullName, null, null, null,
                "77 01 000001", "B", LocalDate.of(2030, 1, 1), (short) 2, null, null, null, null, null,
                "+992000000000", passport, null, null, null, null, null, null, null, null);
    }

    @Test
    void manualModeChangesNothing() {
        var p = policy("MANUAL");
        var req = driverReq("Новое Имя", "B999", "7");
        assertThat(p.guardManualWrite("driver", req, null, null)).isSameAs(req);
        assertThat(p.skipRequired("driver", new Driver(), "UNIFIED")).isEmpty();
    }

    @Test
    void unifiedModeRejectsManualCreation() {
        var p = policy("UNIFIED");
        assertThatThrownBy(() -> p.guardManualWrite("driver", driverReq("Иванов", "A1", "1"), null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Водители ведутся в единой платформе");
    }

    @Test
    void unifiedRecordKeepsETransportFieldsAndTakesModuleFields() {
        var p = policy("UNIFIED");
        var existing = new Driver();
        existing.setFullName("Каримов Карим");
        existing.setPassport("A0000001");
        existing.setPhone("+992111111111");
        existing.setTabNumber("5");
        existing.setDegree((short) 1);

        var guarded = p.guardManualWrite("driver", driverReq("Подменённое Имя", "ПОДМЕНА", "12"), existing, "UNIFIED");

        assertThat(guarded.fullName()).isEqualTo("Каримов Карим");   // поле e-Transport — как было
        assertThat(guarded.passport()).isEqualTo("A0000001");
        assertThat(guarded.phone()).isEqualTo("+992111111111");
        assertThat(guarded.tabNumber()).isEqualTo("12");             // поле модуля — из запроса
        assertThat(guarded.degree()).isEqualTo((short) 2);
        assertThat(guarded.organizationRma()).isEqualTo("025680800");
        assertThat(p.skipRequired("driver", existing, "UNIFIED")).contains("passport").doesNotContain("tabNumber");
    }

    @Test
    void recordsCreatedHereStayEditableInUnifiedMode() {
        var p = policy("UNIFIED");
        var req = driverReq("Новое Имя", "B999", "7");
        assertThat(p.guardManualWrite("driver", req, new Driver(), "MANUAL")).isSameAs(req);
    }

    /**
     * Страховка от ошибки в списках полей: каждое поле, которое у записи из единой платформы
     * сохраняется «как есть», должно читаться из сущности и подходить по типу к полю запроса —
     * иначе сохранение упало бы или поле молча перезаписалось.
     */
    @Test
    void everyProtectedFieldMapsToEntityPropertyOfSameType() throws Exception {
        Map<String, Class<?>[]> forms = Map.of(
                "organization", new Class<?>[]{OrganizationController.OrganizationRequest.class, Organization.class},
                "driver", new Class<?>[]{DriverController.DriverRequest.class, Driver.class},
                "vehicle", new Class<?>[]{VehicleController.VehicleRequest.class, Vehicle.class},
                "employee", new Class<?>[]{EmployeeController.EmployeeRequest.class, Employee.class});
        for (var e : forms.entrySet()) {
            Class<?> request = e.getValue()[0];
            Class<?> entity = e.getValue()[1];
            var components = java.util.Arrays.stream(request.getRecordComponents())
                    .collect(java.util.stream.Collectors.toMap(c -> c.getName(), c -> c.getType()));
            for (String field : MasterDataSourcePolicy.ownedByUnified(e.getKey())) {
                assertThat(components).as("%s: поле %s есть в запросе", e.getKey(), field).containsKey(field);
                var getter = MasterDataSourcePolicy.getter(entity, field);
                Class<?> rt = box(getter.getReturnType());
                assertThat(components.get(field)).as("%s.%s: тип сущности %s", e.getKey(), field, rt)
                        .isAssignableFrom(rt);
            }
        }
    }

    private static Class<?> box(Class<?> t) {
        if (!t.isPrimitive()) return t;
        if (t == boolean.class) return Boolean.class;
        if (t == short.class) return Short.class;
        if (t == int.class) return Integer.class;
        if (t == long.class) return Long.class;
        if (t == double.class) return Double.class;
        return t;
    }
}
