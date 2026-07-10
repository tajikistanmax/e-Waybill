package tj.mintrans.epd.waybill.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaybillTypeTest {

    @Test
    void numberCode_isTwoDigitsForEveryType() {
        for (WaybillType type : WaybillType.values()) {
            assertTrue(type.numberCode().matches("\\d{2}"),
                    "numberCode типа %s должен состоять из 2 цифр: %s".formatted(type, type.numberCode()));
        }
    }

    @Test
    void numberCode_isUniqueAcrossTypes() {
        Set<String> codes = Arrays.stream(WaybillType.values())
                .map(WaybillType::numberCode)
                .collect(Collectors.toSet());
        assertEquals(WaybillType.values().length, codes.size(), "Коды типов в номере должны быть уникальны");
    }

    @Test
    void isPassenger_trueForPassengerTypes() {
        Set<WaybillType> passenger = EnumSet.of(
                WaybillType.WB_BUS, WaybillType.WB_TROLLEYBUS, WaybillType.WB_MINIBUS,
                WaybillType.WB_PAX_INTL, WaybillType.WB_TAXI);
        for (WaybillType type : passenger) {
            assertTrue(type.isPassenger(), type + " — пассажирский тип");
        }
    }

    @Test
    void isPassenger_falseForCargoAndOtherTypes() {
        for (WaybillType type : EnumSet.of(
                WaybillType.WB_TRUCK, WaybillType.WB_TRUCK_INTL,
                WaybillType.WB_SPECIAL, WaybillType.WB_DANGEROUS, WaybillType.WB_CAR)) {
            assertFalse(type.isPassenger(), type + " — не пассажирский тип");
        }
    }
}
