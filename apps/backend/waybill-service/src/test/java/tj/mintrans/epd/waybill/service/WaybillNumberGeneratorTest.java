package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Алгоритм Луна и проверка национального номера RR-YY-TT-NNNNNNN-K.
 * Метод next() требует EntityManager/БД и здесь не тестируется.
 */
class WaybillNumberGeneratorTest {

    // ------------------------------------------------------------ luhnCheckDigit

    @Test
    void luhnCheckDigit_classicWikipediaExample() {
        // 7992739871 → контрольный разряд 3 (эталонный пример алгоритма Луна)
        assertEquals(3, WaybillNumberGenerator.luhnCheckDigit("7992739871"));
    }

    @Test
    void luhnCheckDigit_visaTestNumberPayload() {
        // 4532015112830366 — валидный тестовый номер: контрольный разряд 6
        assertEquals(6, WaybillNumberGenerator.luhnCheckDigit("453201511283036"));
    }

    @Test
    void luhnCheckDigit_singleDigits() {
        assertEquals(0, WaybillNumberGenerator.luhnCheckDigit("0")); // 0*2=0 → 0
        assertEquals(8, WaybillNumberGenerator.luhnCheckDigit("1")); // 1*2=2 → 10-2=8
        assertEquals(1, WaybillNumberGenerator.luhnCheckDigit("9")); // 9*2=18→9 → 10-9=1
    }

    @Test
    void luhnCheckDigit_waybillPayload() {
        // Полезная нагрузка номера ПЛ (13 цифр): RR=00, YY=26, TT=06, NNNNNNN=0000001
        assertEquals(2, WaybillNumberGenerator.luhnCheckDigit("0026060000001"));
    }

    // ------------------------------------------------------------ isValid

    @Test
    void isValid_correctNumber_true() {
        assertTrue(WaybillNumberGenerator.isValid("00-26-06-0000001-2"));
        // Формат без дефисов также распознаётся
        assertTrue(WaybillNumberGenerator.isValid("00260600000012"));
    }

    @Test
    void isValid_corruptedCheckDigit_false() {
        assertFalse(WaybillNumberGenerator.isValid("00-26-06-0000001-3"));
        assertFalse(WaybillNumberGenerator.isValid("00-26-06-0000001-9"));
    }

    @Test
    void isValid_wrongLength_false() {
        assertFalse(WaybillNumberGenerator.isValid("00-26-06-000001-2"));    // 13 цифр
        assertFalse(WaybillNumberGenerator.isValid("00-26-06-00000001-2"));  // 15 цифр
        assertFalse(WaybillNumberGenerator.isValid(""));
    }

    @Test
    void isValid_nonDigitCharacters_false() {
        assertFalse(WaybillNumberGenerator.isValid("AA-26-06-0000001-2"));
    }
}
