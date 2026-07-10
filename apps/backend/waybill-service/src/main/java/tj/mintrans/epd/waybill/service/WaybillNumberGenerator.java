package tj.mintrans.epd.waybill.service;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import tj.mintrans.epd.waybill.domain.WaybillType;

import java.time.Year;

/**
 * Национальный номер путевого листа: RR-YY-TT-NNNNNNN-K (раздел 11.5 ТЗ).
 * RR — код региона (00 — центральный/API), YY — год, TT — код типа,
 * NNNNNNN — порядковый номер, K — контрольный разряд по алгоритму Луна.
 */
@Component
public class WaybillNumberGenerator {

    private final EntityManager em;

    public WaybillNumberGenerator(EntityManager em) {
        this.em = em;
    }

    public String next(Short regionId, WaybillType type) {
        long seq = ((Number) em.createNativeQuery("SELECT nextval('waybill_number_seq')").getSingleResult()).longValue();
        String rr = String.format("%02d", regionId == null ? 0 : regionId);
        String yy = String.format("%02d", Year.now().getValue() % 100);
        String tt = type.numberCode();
        String nn = String.format("%07d", seq % 10_000_000);
        String digits = rr + yy + tt + nn;
        int check = luhnCheckDigit(digits);
        return "%s-%s-%s-%s-%d".formatted(rr, yy, tt, nn, check);
    }

    static int luhnCheckDigit(String digits) {
        int sum = 0;
        boolean doubleIt = true; // начиная с крайней правой цифры (позиция контрольного разряда — следующая)
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return (10 - (sum % 10)) % 10;
    }

    /** Проверка номера, включая контрольный разряд. */
    public static boolean isValid(String number) {
        String digits = number.replace("-", "");
        if (!digits.matches("\\d{14}")) return false;
        return luhnCheckDigit(digits.substring(0, 13)) == (digits.charAt(13) - '0');
    }
}
