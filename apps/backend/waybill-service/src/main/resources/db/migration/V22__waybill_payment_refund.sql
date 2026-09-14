-- Возврат оплаты путевого листа (§16 QA): статус CONFIRMED → REFUNDED.
-- Симметрично подтверждению (confirmed_by/confirmed_at) фиксируем, КТО и КОГДА оформил
-- возврат, его причину и сумму. Возврат ПОЛНЫЙ (refund_amount = уплаченной сумме).
-- Фактическое движение денег — ВНЕШНЕЕ (банк-шлюз/агрегатор); здесь фиксируется только
-- платформенное состояние возврата (refund_external_ref — № возвратной транзакции шлюза,
-- когда интеграция будет подключена).
--
-- Значение статуса REFUNDED укладывается в существующий VARCHAR(12) (PENDING | CONFIRMED | REFUNDED).
-- Существующие строки не изменяются: все новые поля NULL-able и без DEFAULT.

ALTER TABLE waybill_payment
    ADD COLUMN refund_reason       VARCHAR(500),
    ADD COLUMN refund_amount       NUMERIC(10,2),
    ADD COLUMN refunded_by         VARCHAR(100),
    ADD COLUMN refunded_at         TIMESTAMPTZ,
    ADD COLUMN refund_external_ref VARCHAR(100);
