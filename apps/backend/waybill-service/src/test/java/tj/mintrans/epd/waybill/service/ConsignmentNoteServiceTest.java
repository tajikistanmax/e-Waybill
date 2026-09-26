package tj.mintrans.epd.waybill.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tj.mintrans.epd.waybill.domain.ConsignmentNote;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.repository.ConsignmentNoteRepository;
import tj.mintrans.epd.waybill.repository.WorkDayRepository;
import tj.mintrans.epd.waybill.web.error.ApiErrors.ConflictException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Борхаты 2-Б (legacy CargoWaybillRequest + CargoFuelBase::calcP/calcZ). */
class ConsignmentNoteServiceTest {

    private final UUID id = UUID.randomUUID();
    private Waybill wb;
    private ConsignmentNoteService service;

    @BeforeEach
    void setUp() {
        wb = new Waybill();
        ReflectionTestUtils.setField(wb, "id", id);
        wb.setWaybillType(WaybillType.WB_TRUCK);
        wb.setStatus(WaybillStatus.ACTIVE);
        wb.setValidFrom(OffsetDateTime.of(2026, 9, 20, 8, 0, 0, 0, ZoneOffset.ofHours(5)));
        wb.setValidTo(wb.getValidFrom().plusDays(15));
        WaybillService waybills = mock(WaybillService.class);
        when(waybills.get(id)).thenReturn(wb);
        ConsignmentNoteRepository repo = mock(ConsignmentNoteRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new ConsignmentNoteService(repo, mock(WorkDayRepository.class), waybills);
    }

    private static ConsignmentNoteService.NoteData data(int kind, LocalDate date, String payer, String weight, Integer trips) {
        return new ConsignmentNoteService.NoteData(kind, date, null,
                null, payer, null, "Фиристанда", null, null, "Гиранда", null,
                null, kind == 2 ? "Экспедитор" : null,
                null, "Семент", 101L,
                new BigDecimal("10"), weight == null ? null : new BigDecimal(weight), new BigDecimal("40"),
                trips, new BigDecimal("2"), null, null);
    }

    @Test
    @DisplayName("замимаи 1: сохраняются поля, рейсы по умолчанию 1")
    void createKind1() {
        ConsignmentNote n = service.create(id, data(1, LocalDate.of(2026, 9, 21), "Мизоҷ", "12.5", null), "disp");
        assertThat(n.getKind()).isEqualTo((short) 1);
        assertThat(n.getTrips()).isEqualTo(1);
        assertThat(n.getPayerName()).isEqualTo("Мизоҷ");
        assertThat(n.getCreatedBy()).isEqualTo("disp");
        assertThat(n.transportWork()).isCloseTo(500d, within(1e-9));   // 12.5 т × 40 км × 1
    }

    @Test
    @DisplayName("обязательные поля legacy: без заказчика или массы — 422")
    void requiredFields() {
        assertThatThrownBy(() -> service.create(id, data(1, LocalDate.of(2026, 9, 21), null, "5", 1), "d"))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("Мизоҷ");
        assertThatThrownBy(() -> service.create(id, data(1, LocalDate.of(2026, 9, 21), "М", null, 1), "d"))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("Ҳаҷми бор");
    }

    @Test
    @DisplayName("дата вне срока листа (выпуск + 15 сут → 20.09…04.10) — 422")
    void dateWindow() {
        assertThatThrownBy(() -> service.create(id, data(1, LocalDate.of(2026, 10, 5), "М", "5", 1), "d"))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("срока действия");
        assertThat(service.create(id, data(1, LocalDate.of(2026, 10, 4), "М", "5", 1), "d")).isNotNull();
    }

    @Test
    @DisplayName("не 2-Б и закрытый лист — отказ")
    void onlyOpenTruck() {
        wb.setStatus(WaybillStatus.COMPLETED);
        assertThatThrownBy(() -> service.create(id, data(1, LocalDate.of(2026, 9, 21), "М", "5", 1), "d"))
                .isInstanceOf(ConflictException.class);
        wb.setStatus(WaybillStatus.ACTIVE);
        wb.setWaybillType(WaybillType.WB_BUS);
        assertThatThrownBy(() -> service.create(id, data(1, LocalDate.of(2026, 9, 21), "М", "5", 1), "d"))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("2-Б");
    }

    @Test
    @DisplayName("итоги: P = Σ масса·расстояние·рейсы (зам.1) + Σ масса·расстояние (зам.2); Z = Σ рейсов + число зам.2")
    void totals() {
        ConsignmentNote a = service.create(id, data(1, LocalDate.of(2026, 9, 21), "М", "10", 3), "d");
        ConsignmentNote b = service.create(id, data(2, LocalDate.of(2026, 9, 22), "М", "5", 7), "d");
        var t = ConsignmentNoteService.Totals.of(List.of(a, b));
        assertThat(t.count()).isEqualTo(2);
        assertThat(t.transportWork()).isCloseTo(10 * 40 * 3 + 5 * 40, within(1e-9));   // 1400 т·км
        assertThat(t.trips()).isCloseTo(3 + 1, within(1e-9));                         // рейсы зам.2 не множат
        assertThat(t.specialDistance()).isCloseTo(2 * 3 + 2, within(1e-9));            // L1
        assertThat(t.weight()).isCloseTo(10 * 3 + 5, within(1e-9));                    // тонн перевезено
    }
}
