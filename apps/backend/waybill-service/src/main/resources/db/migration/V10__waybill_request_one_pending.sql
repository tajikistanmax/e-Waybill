-- Правило «одна заявка в работе»: у водителя не может быть двух PENDING-заявок одновременно.
-- Кодовая проверка existsByDriverRmaAndStatus в WaybillRequestService.create() — это check-then-act
-- без блокировки, поэтому два параллельных POST могли создать дубли. Партиал-уникальный индекс
-- закрывает гонку на уровне БД: второй insert → DataIntegrityViolationException → 409 (ApiErrors).
CREATE UNIQUE INDEX uq_wbreq_driver_pending ON waybill_request (driver_rma) WHERE status = 'PENDING';
