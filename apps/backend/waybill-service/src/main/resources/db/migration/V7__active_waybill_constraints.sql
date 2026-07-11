-- Инвариант «один ДЕЙСТВУЮЩИЙ путевой лист на ТС и на водителя» — теперь на уровне БД.
-- Прежде он держался только check-then-insert в runBlockingChecks (findBy…AndStatusIn),
-- что уязвимо к TOCTOU-гонке: два конкурентных create под READ_COMMITTED оба видят
-- отсутствие активного ПЛ и оба вставляют → два одновременно действующих госдокумента
-- (обход антифрод-правила, гонка портал↔агрегатор). Частичные уникальные индексы по
-- открытым статусам делают вторую параллельную вставку/переход невозможной.
-- Статусы соответствуют WaybillStatus.OPEN_STATUSES (DRAFT намеренно исключён —
-- черновик ещё не «действует» и не блокирует ТС/водителя).

CREATE UNIQUE INDEX uq_active_waybill_vehicle ON waybill (vehicle_reg_number)
    WHERE status IN ('CREATED', 'AWAITING_PAYMENT', 'PAID', 'READY', 'ISSUED', 'ACTIVE');

CREATE UNIQUE INDEX uq_active_waybill_driver ON waybill (driver_rma)
    WHERE status IN ('CREATED', 'AWAITING_PAYMENT', 'PAID', 'READY', 'ISSUED', 'ACTIVE');
