package tj.mintrans.epd.waybill.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.domain.ConsignmentNote;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillStatus;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.repository.ConsignmentNoteRepository;
import tj.mintrans.epd.waybill.repository.WorkDayRepository;
import tj.mintrans.epd.waybill.web.error.ApiErrors.ConflictException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.UnprocessableException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Борхатҳо (накладные замимаи 1 / 2) путевого листа 2-Б — N на лист, как legacy {@code cargo_waybills}
 * ({@code CargoWaybillCrudController}, {@code CargoWaybillRequest}). Из строк собираются P, Z и пробег со
 * спецработой сдельного листа ({@code CargoFuelBase::calcP/calcZ}, {@code SpecialMoverFuel}).
 */
@Service
public class ConsignmentNoteService {

    /** Данные борхата. Стороны и груз — id справочника плюс снимок наименования. */
    public record NoteData(
            Integer kind,
            LocalDate noteDate,
            UUID workDayId,
            UUID payerId, String payerName,
            UUID senderId, String senderName, String senderAddress,
            UUID receiverId, String receiverName, String receiverAddress,
            UUID forwarderId, String forwarderName,
            UUID cargoId, String cargoName, Long cargoNumber,
            BigDecimal cargoAmount, BigDecimal cargoWeight, BigDecimal distance,
            Integer trips, BigDecimal specialDistance,
            OffsetDateTime entryTime, String invoiceNumber) {
    }

    /** Итоги борхатов листа — те же P, Z, L1, что уходят в расчёт. */
    public record Totals(int count, double transportWork, double trips, double specialDistance, double weight) {
        public static Totals of(List<ConsignmentNote> notes) {
            double p = 0, z = 0, l = 0, w = 0;
            for (ConsignmentNote n : notes) {
                p += n.transportWork();
                z += n.tripsCount();
                l += n.specialWork();
                w += n.weight() * (n.getKind() == 2 ? 1 : Math.max(0, n.getTrips() == null ? 0 : n.getTrips()));
            }
            return new Totals(notes.size(), round(p), z, round(l), round(w));
        }

        private static double round(double v) {
            return Math.round(v * 1000d) / 1000d;
        }
    }

    public record NotesView(List<ConsignmentNote> notes, Totals totals) {
    }

    private final ConsignmentNoteRepository notes;
    private final WorkDayRepository workDays;
    private final WaybillService waybillService;

    public ConsignmentNoteService(ConsignmentNoteRepository notes, WorkDayRepository workDays,
                                  WaybillService waybillService) {
        this.notes = notes;
        this.workDays = workDays;
        this.waybillService = waybillService;
    }

    @Transactional(readOnly = true)
    public NotesView list(UUID waybillId) {
        waybillService.get(waybillId);   // область видимости листа (тенант)
        List<ConsignmentNote> list = notes.findByWaybillIdOrderByNoteDateAscNumberAsc(waybillId);
        return new NotesView(list, Totals.of(list));
    }

    @Transactional(readOnly = true)
    public ConsignmentNote get(UUID waybillId, UUID noteId) {
        waybillService.get(waybillId);
        return own(waybillId, noteId);
    }

    @Transactional
    public ConsignmentNote create(UUID waybillId, NoteData d, String actor) {
        Waybill wb = waybillService.get(waybillId);
        requireEditable(wb);
        ConsignmentNote n = new ConsignmentNote();
        n.setWaybillId(waybillId);
        n.setCreatedBy(actor);
        apply(wb, n, d);
        return notes.save(n);
    }

    @Transactional
    public ConsignmentNote update(UUID waybillId, UUID noteId, NoteData d) {
        Waybill wb = waybillService.get(waybillId);
        requireEditable(wb);
        ConsignmentNote n = own(waybillId, noteId);
        apply(wb, n, d);
        return notes.save(n);
    }

    @Transactional
    public void delete(UUID waybillId, UUID noteId) {
        Waybill wb = waybillService.get(waybillId);
        requireEditable(wb);
        notes.delete(own(waybillId, noteId));
    }

    // ------------------------------------------------------------------

    /** Борхаты ведёт диспетчер до закрытия листа: черновик, выданный, на линии, возвращённый, просроченный на линии. */
    private void requireEditable(Waybill wb) {
        if (wb.getWaybillType() != WaybillType.WB_TRUCK && wb.getWaybillType() != WaybillType.WB_DANGEROUS) {
            throw new UnprocessableException("Борхаты (замимаи 1/2) ведутся только у путевого листа 2-Б");
        }
        WaybillStatus st = wb.getStatus();
        boolean closed = st == WaybillStatus.BLOCKED || (st.isTerminal() && !waybillService.isOverdueOnLine(wb));
        if (closed) {
            throw new ConflictException("Борхаты нельзя изменить у листа в статусе " + st);
        }
    }

    private void apply(Waybill wb, ConsignmentNote n, NoteData d) {
        int kind = d.kind() == null ? 1 : d.kind();
        if (kind != 1 && kind != 2) {
            throw new UnprocessableException("Вид борхата — 1 (замимаи 1) или 2 (замимаи 2)");
        }
        // Обязательные поля — legacy CargoWaybillRequest: client_id, sender_id, receiver_id, cargo_id,
        // cargo_amount, cargo_capacity, distance.
        require(d.noteDate() != null, "Сана (дата борхата)");
        require(present(d.payerId(), d.payerName()), "Мизоҷ (заказчик)");
        require(present(d.senderId(), d.senderName()), "Фиристанда (отправитель)");
        require(present(d.receiverId(), d.receiverName()), "Гиранда (получатель)");
        require(present(d.cargoId(), d.cargoName()), "Бор (груз)");
        require(d.cargoAmount() != null, "Миқдори бор");
        require(d.cargoWeight() != null, "Ҳаҷми бор (тн)");
        require(d.distance() != null, "Масофа (км)");
        if (d.cargoAmount().signum() < 0 || d.cargoWeight().signum() < 0 || d.distance().signum() < 0
                || (d.specialDistance() != null && d.specialDistance().signum() < 0)) {
            throw new UnprocessableException("Количество, масса и расстояния борхата не могут быть отрицательными");
        }
        if (d.cargoWeight().compareTo(BigDecimal.valueOf(1000)) > 0) {
            throw new UnprocessableException("Масса груза борхата — не более 1000 т");
        }
        if (d.distance().compareTo(BigDecimal.valueOf(5000)) > 0) {
            throw new UnprocessableException("Расстояние борхата — не более 5000 км");
        }
        int trips = d.trips() == null ? 1 : d.trips();
        if (trips < 1 || trips > 999) {
            throw new UnprocessableException("Шумораи рейс — от 1 до 999");
        }
        // Дата — в сроке действия листа (как у рабочих дней: выпуск … выпуск + N − 1).
        if (wb.getValidFrom() != null && wb.getValidTo() != null) {
            LocalDate from = wb.getValidFrom().toLocalDate();
            LocalDate to = from.plusDays(Math.max(0, WaybillService.validityDays(wb) - 1));
            if (d.noteDate().isBefore(from) || d.noteDate().isAfter(to)) {
                throw new UnprocessableException("Дата борхата вне срока действия путевого листа (%s — %s)".formatted(from, to));
            }
        }
        if (d.workDayId() != null) {
            var day = workDays.findById(d.workDayId()).orElseThrow(() -> new NotFoundException("Рабочий день не найден"));
            if (!wb.getId().equals(day.getWaybillId())) {
                throw new UnprocessableException("Рабочий день не относится к этому путевому листу");
            }
        }
        n.setKind((short) kind);
        n.setNoteDate(d.noteDate());
        n.setWorkDayId(d.workDayId());
        n.setPayerId(d.payerId());
        n.setPayerName(trim(d.payerName()));
        n.setSenderId(d.senderId());
        n.setSenderName(trim(d.senderName()));
        n.setSenderAddress(trim(d.senderAddress()));
        n.setReceiverId(d.receiverId());
        n.setReceiverName(trim(d.receiverName()));
        n.setReceiverAddress(trim(d.receiverAddress()));
        n.setForwarderId(d.forwarderId());
        n.setForwarderName(trim(d.forwarderName()));
        n.setCargoId(d.cargoId());
        n.setCargoName(trim(d.cargoName()));
        n.setCargoNumber(d.cargoNumber());
        n.setCargoAmount(d.cargoAmount());
        n.setCargoWeight(d.cargoWeight());
        n.setDistance(d.distance());
        n.setTrips(trips);
        n.setSpecialDistance(d.specialDistance());
        n.setEntryTime(d.entryTime());
        n.setInvoiceNumber(trim(d.invoiceNumber()));
    }

    private ConsignmentNote own(UUID waybillId, UUID noteId) {
        ConsignmentNote n = notes.findById(noteId).orElseThrow(() -> new NotFoundException("Борхат не найден"));
        if (!waybillId.equals(n.getWaybillId())) {
            throw new NotFoundException("Борхат не найден");
        }
        return n;
    }

    private static void require(boolean ok, String field) {
        if (!ok) {
            throw new UnprocessableException("Не заполнено обязательное поле борхата: " + field);
        }
    }

    private static boolean present(UUID id, String name) {
        return id != null || (name != null && !name.isBlank());
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
