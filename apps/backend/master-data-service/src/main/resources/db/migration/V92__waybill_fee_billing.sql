-- Платная выдача путевых листов (тариф по типу ПЛ + баланс организации, непривязанный к
-- блокировке выдачи — решение владельца 31.10.2026: пост-оплата, а не предоплата-гейт).
-- Тариф — отдельная таблица, НЕ движок политик (policy): там ORGANIZATION-уровень пишет
-- сам COMPANY_ADMIN своей организации (assertCanWrite в PolicyController) — для денежной
-- ставки это недопустимо, тариф назначает только SYSTEM_ADMIN.

CREATE TABLE waybill_fee_tariff (
    id               UUID PRIMARY KEY,
    waybill_type     VARCHAR(32)  NOT NULL,
    organization_id  UUID         NULL REFERENCES organization(id),
    amount_tjs       NUMERIC(12,2) NOT NULL CHECK (amount_tjs >= 0),
    updated_by       VARCHAR(100),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- NULL organization_id = национальная ставка по умолчанию для типа ПЛ (одна на тип).
CREATE UNIQUE INDEX uq_tariff_national ON waybill_fee_tariff(waybill_type) WHERE organization_id IS NULL;
-- Переопределение по организации — не более одной ставки на (тип, организация).
CREATE UNIQUE INDEX uq_tariff_org ON waybill_fee_tariff(waybill_type, organization_id) WHERE organization_id IS NOT NULL;

CREATE TABLE organization_balance (
    organization_id  UUID PRIMARY KEY REFERENCES organization(id),
    balance_tjs      NUMERIC(14,2) NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Выписка начислений/пополнений (как банковская история). DEBIT — автосписание при выдаче
-- ПЛ (Т1); CREDIT — пополнение, которое вносит SYSTEM_ADMIN по факту внешнего банковского
-- перевода (деньги двигаются вне платформы, как сейчас с лицензиями/контрольными карточками).
CREATE TABLE billing_ledger (
    id               UUID PRIMARY KEY,
    organization_id  UUID         NOT NULL REFERENCES organization(id),
    direction        VARCHAR(6)   NOT NULL CHECK (direction IN ('CREDIT', 'DEBIT')),
    amount_tjs       NUMERIC(12,2) NOT NULL CHECK (amount_tjs > 0),
    waybill_id       UUID         NULL,
    waybill_type     VARCHAR(32)  NULL,
    reference        VARCHAR(255) NULL,
    note             TEXT         NULL,
    created_by       VARCHAR(100),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- Идемпотентность: один и тот же путевой лист не может списать баланс дважды (повтор
-- запроса waybill-service при сетевом сбое/ретрае — не двойное списание).
CREATE UNIQUE INDEX uq_ledger_waybill_debit ON billing_ledger(waybill_id) WHERE waybill_id IS NOT NULL;
CREATE INDEX idx_ledger_org_created ON billing_ledger(organization_id, created_at DESC);
