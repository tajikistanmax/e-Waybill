-- Уведомления организации-перевозчику о ключевых событиях путевого листа
-- (медотказ, техотказ, готов к выдаче, блокировка инспектором, просрочка).
-- Генерируются после commit из доменного события WaybillStatusChanged.

CREATE TABLE notification (
    id            UUID PRIMARY KEY,
    recipient_rma VARCHAR(20)  NOT NULL,   -- РМА организации-получателя
    waybill_id    UUID,                    -- связанный путевой лист (для перехода)
    kind          VARCHAR(40)  NOT NULL,   -- статус-источник (MED_REJECTED, READY, BLOCKED, …)
    title         VARCHAR(200) NOT NULL,
    body          VARCHAR(500),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    read_at       TIMESTAMPTZ              -- null = непрочитано
);

CREATE INDEX idx_notification_recipient ON notification (recipient_rma, created_at DESC);
CREATE INDEX idx_notification_unread ON notification (recipient_rma) WHERE read_at IS NULL;
