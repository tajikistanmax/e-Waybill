-- Оплата путевого листа (статусы AWAITING_PAYMENT → PAID, waybill-statuses.yaml).
-- Одна запись на документ; подтверждение — платёжный шлюз или бухгалтер (роль ACCOUNTANT).

CREATE TABLE waybill_payment (
    id           UUID PRIMARY KEY,
    waybill_id   UUID          NOT NULL UNIQUE REFERENCES waybill (id),
    amount       NUMERIC(10,2) NOT NULL,
    currency     VARCHAR(3)    NOT NULL DEFAULT 'TJS',
    status       VARCHAR(12)   NOT NULL DEFAULT 'PENDING',  -- PENDING | CONFIRMED
    method       VARCHAR(20),                               -- BANK | GATEWAY | SUBSCRIPTION | CASH
    external_ref VARCHAR(100),                              -- № транзакции шлюза / платёжного поручения
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    confirmed_at TIMESTAMPTZ,
    confirmed_by VARCHAR(100)
);
