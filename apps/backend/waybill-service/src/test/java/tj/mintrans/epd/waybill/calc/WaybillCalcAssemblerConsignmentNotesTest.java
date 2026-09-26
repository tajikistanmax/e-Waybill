package tj.mintrans.epd.waybill.calc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tj.mintrans.epd.waybill.client.MasterDataClient;
import tj.mintrans.epd.waybill.domain.ConsignmentNote;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.repository.ConsignmentNoteRepository;
import tj.mintrans.epd.waybill.repository.FuelRecordRepository;
import tj.mintrans.epd.waybill.repository.WorkDayRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** P, Z и L1 сдельного 2-Б — из борхатов (legacy CargoFuelBase::calcP/calcZ, только type_of_shipment = 1). */
class WaybillCalcAssemblerConsignmentNotesTest {

    private ConsignmentNoteRepository notesRepo;
    private WaybillCalcAssembler assembler;

    @BeforeEach
    void setUp() {
        notesRepo = mock(ConsignmentNoteRepository.class);
        assembler = new WaybillCalcAssembler(mock(WaybillCalcEngine.class), mock(MasterDataClient.class),
                mock(WorkDayRepository.class), mock(FuelRecordRepository.class));
        assembler.setConsignmentNotes(notesRepo);
        ConsignmentNote a = note(1, "10", "40", 3, "2");
        ConsignmentNote b = note(2, "5", "40", 1, "4");
        when(notesRepo.findByWaybillIdOrderByNoteDateAscNumberAsc(any())).thenReturn(List.of(a, b));
    }

    private static ConsignmentNote note(int kind, String weight, String distance, int trips, String special) {
        ConsignmentNote n = new ConsignmentNote();
        n.setKind((short) kind);
        n.setCargoWeight(new BigDecimal(weight));
        n.setDistance(new BigDecimal(distance));
        n.setTrips(trips);
        n.setSpecialDistance(new BigDecimal(special));
        return n;
    }

    private static Waybill truck(String shipmentKind) {
        Waybill wb = new Waybill();
        ReflectionTestUtils.setField(wb, "id", UUID.randomUUID());
        wb.setWaybillType(WaybillType.WB_TRUCK);
        Map<String, Object> td = new HashMap<>();
        if (shipmentKind != null) {
            td.put("shipmentKind", shipmentKind);
        }
        wb.setTypeData(td);
        return wb;
    }

    @Test
    @DisplayName("сдельный: P = 10·40·3 + 5·40 = 1400, Z = 3 + 1 = 4, L1 = 2·3 + 4 = 10")
    void piecework() {
        List<String> notes = new ArrayList<>();
        var s = assembler.fromConsignmentNotes(truck("PIECEWORK"), WaybillCalcAssembler.Supplement.empty(), notes);
        assertThat(s.transportWork()).isCloseTo(1400d, within(1e-9));
        assertThat(s.trips()).isCloseTo(4d, within(1e-9));
        assertThat(s.specialDistance()).isCloseTo(10d, within(1e-9));
        assertThat(notes).anyMatch(x -> x.contains("борхатам: 2"));
    }

    @Test
    @DisplayName("повременный — борхаты в P/Z не идут (legacy: только корбайъ)")
    void hourlyIgnored() {
        var s = assembler.fromConsignmentNotes(truck("HOURLY"), WaybillCalcAssembler.Supplement.empty(), new ArrayList<>());
        assertThat(s.transportWork()).isNull();
        assertThat(s.trips()).isNull();
    }

    @Test
    @DisplayName("явно переданное в запросе расчёта значение P не перезаписывается")
    void explicitWins() {
        var sup = new WaybillCalcAssembler.Supplement(null, null, null, 77d, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
        var s = assembler.fromConsignmentNotes(truck(null), sup, new ArrayList<>());
        assertThat(s.transportWork()).isEqualTo(77d);
        assertThat(s.trips()).isCloseTo(4d, within(1e-9));
    }
}
