package tj.mintrans.epd.waybill.service;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tj.mintrans.epd.waybill.config.CurrentUser;
import tj.mintrans.epd.waybill.domain.Waybill;
import tj.mintrans.epd.waybill.domain.WaybillType;
import tj.mintrans.epd.waybill.print.WaybillPrintService;
import tj.mintrans.epd.waybill.repository.WaybillRepository;
import tj.mintrans.epd.waybill.web.error.ApiErrors.ForbiddenException;
import tj.mintrans.epd.waybill.web.error.ApiErrors.NotFoundException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Внешние кабинеты накладных (MIGRATION.md 1.1/3.11): грузоотправитель и экспедитор видят и правят накладные
 * (борхат 2-Б, СМР 5Б-БМ) своих клиентов, таможенник — все СМР и подтверждает их. Область — НЕ организация
 * (у внешних пользователей нет {@code organization_rma}), а клиенты из claim {@code client_ids} / роль.
 */
@Service
public class ConsignmentCabinetService {

    public record Filter(LocalDate from, LocalDate to, String q, boolean onlyUnconfirmed) {
    }

    public record PageResult(List<Waybill> content, int page, int size, long totalElements, int totalPages, String scope) {
    }

    private final WaybillRepository waybills;
    private final WaybillService waybillService;
    private final WaybillPrintService print;
    private final CurrentUser currentUser;

    public ConsignmentCabinetService(WaybillRepository waybills, WaybillService waybillService,
                                     WaybillPrintService print, CurrentUser currentUser) {
        this.waybills = waybills;
        this.waybillService = waybillService;
        this.print = print;
        this.currentUser = currentUser;
    }

    private Set<String> roles() {
        Set<String> r = new LinkedHashSet<>();
        for (String role : new String[]{ConsignmentAccess.ROLE_SENDER, ConsignmentAccess.ROLE_FORWARDER, ConsignmentAccess.ROLE_CUSTOMS}) {
            if (currentUser.hasRole(role)) {
                r.add(role);
            }
        }
        return r;
    }

    @Transactional(readOnly = true)
    public PageResult list(Filter f, int page, int size) {
        Set<String> roles = roles();
        Set<String> clients = currentUser.clientIds();
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), 500);
        boolean customs = roles.contains(ConsignmentAccess.ROLE_CUSTOMS);
        boolean sender = roles.contains(ConsignmentAccess.ROLE_SENDER) && !clients.isEmpty();
        boolean forwarder = roles.contains(ConsignmentAccess.ROLE_FORWARDER) && !clients.isEmpty();
        if (!customs && !sender && !forwarder) {
            return new PageResult(List.of(), p, s, 0, 0, "none");
        }
        Specification<Waybill> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.notEqual(root.get("source"), "MIGRATED"));
            Expression<String> senderName = json(cb, root.get("typeData"), "senderName");
            Expression<String> receiverName = json(cb, root.get("typeData"), "receiverName");
            Expression<String> forwarderName = json(cb, root.get("typeData"), "forwarderName");
            Predicate hasConsignment = cb.or(cb.and(cb.isNotNull(senderName), cb.notEqual(senderName, "")),
                    cb.and(cb.isNotNull(receiverName), cb.notEqual(receiverName, "")),
                    cb.and(cb.isNotNull(forwarderName), cb.notEqual(forwarderName, "")));
            List<Predicate> scope = new ArrayList<>();
            if (customs) {
                scope.add(cb.and(cb.equal(root.get("waybillType"), WaybillType.WB_TRUCK_INTL), hasConsignment));
            }
            if (sender) {
                scope.add(cb.and(root.get("waybillType").in(List.of(WaybillType.WB_TRUCK, WaybillType.WB_DANGEROUS, WaybillType.WB_TRUCK_INTL)),
                        cb.lower(json(cb, root.get("typeData"), "senderId")).in(clients)));
            }
            if (forwarder) {
                scope.add(cb.and(root.get("waybillType").in(List.of(WaybillType.WB_TRUCK, WaybillType.WB_DANGEROUS, WaybillType.WB_TRUCK_INTL)),
                        cb.lower(json(cb, root.get("typeData"), "forwarderId")).in(clients)));
            }
            ps.add(cb.or(scope.toArray(Predicate[]::new)));
            if (f.from() != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), WaybillPeriodScan.lower(f.from())));
            }
            if (f.to() != null) {
                ps.add(cb.lessThan(root.get("createdAt"), WaybillPeriodScan.upper(f.to())));
            }
            if (f.onlyUnconfirmed()) {
                Expression<String> confirmedAt = json(cb, root.get("typeData"), "customsConfirmedAt");
                ps.add(cb.or(cb.isNull(confirmedAt), cb.equal(confirmedAt, "")));
            }
            if (f.q() != null && !f.q().isBlank()) {
                String pattern = "%" + f.q().trim().toLowerCase() + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("number")), pattern),
                        cb.like(cb.lower(root.get("vehicleRegNumber")), pattern),
                        cb.like(cb.lower(senderName), pattern),
                        cb.like(cb.lower(receiverName), pattern),
                        cb.like(cb.lower(json(cb, root.get("typeData"), "cargoName")), pattern),
                        cb.like(cb.lower(json(cb, root.get("organizationSnapshot"), "name")), pattern)));
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
        Page<Waybill> pg = waybills.findAll(spec, PageRequest.of(p, s, Sort.by(Sort.Direction.DESC, "createdAt")));
        return new PageResult(pg.getContent(), p, s, pg.getTotalElements(), pg.getTotalPages(),
                customs ? "customs" : sender && forwarder ? "sender+forwarder" : sender ? "sender" : "forwarder");
    }

    @Transactional(readOnly = true)
    public Waybill get(UUID id) {
        Waybill wb = waybills.findById(id).orElseThrow(() -> new NotFoundException("Накладная не найдена"));
        if (!ConsignmentAccess.canView(roles(), currentUser.clientIds(), wb)) {
            throw new NotFoundException("Накладная не найдена");   // не раскрываем существование чужих ПЛ
        }
        return wb;
    }

    @Transactional
    public Waybill update(UUID id, WaybillService.ConsignmentUpdate data) {
        Waybill wb = get(id);
        if (!ConsignmentAccess.canEdit(roles(), currentUser.clientIds(), wb)) {
            throw new ForbiddenException("Правка накладной доступна только её отправителю/экспедитору");
        }
        return waybillService.updateConsignmentByClient(wb.getId(), data);
    }

    @Transactional
    public Waybill confirmCustoms(UUID id) {
        Waybill wb = get(id);
        if (!ConsignmentAccess.canConfirmCustoms(roles(), wb)) {
            throw new ForbiddenException("Таможенное подтверждение — только таможенник и только СМР");
        }
        return waybillService.confirmCustoms(wb.getId(), currentUser.fullName().orElse(null), currentUser.username().orElse("customs"));
    }

    @Transactional(readOnly = true)
    public byte[] printPdf(UUID id) {
        return print.renderConsignmentPdf(get(id));
    }

    private static Expression<String> json(CriteriaBuilder cb, Path<?> column, String key) {
        return cb.function("jsonb_extract_path_text", String.class, column, cb.literal(key));
    }
}
