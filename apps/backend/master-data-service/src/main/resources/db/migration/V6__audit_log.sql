-- Журнал аудита (административная подсистема «Настройки», требование госплатформы):
-- неизменяемая запись «кто/когда/что изменил» со старым и новым значением.
-- Обобщённая схема (entity_type) — первый потребитель: движок политик; расширяется
-- на организации/водителей/ТС и др. без изменения схемы.

CREATE TABLE audit_log (
    id          UUID PRIMARY KEY,
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actor       VARCHAR(100),           -- субъект (preferred_username/sub из JWT), null для системных
    actor_org   VARCHAR(20),            -- РМА организации актора (если tenant-scoped)
    action      VARCHAR(20)  NOT NULL,  -- CREATE | UPDATE | DELETE
    entity_type VARCHAR(40)  NOT NULL,  -- POLICY | ORGANIZATION | DRIVER | VEHICLE | EMPLOYEE
    entity_key  VARCHAR(160),           -- человекочитаемый идентификатор сущности
    old_value   TEXT,                   -- прежнее значение (null для CREATE)
    new_value   TEXT                    -- новое значение (null для DELETE)
);

CREATE INDEX idx_audit_occurred ON audit_log (occurred_at DESC);
CREATE INDEX idx_audit_entity ON audit_log (entity_type, entity_key);
