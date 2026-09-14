-- Ядро: путевые листы, титулы, события статусов.
-- Снимки мастер-данных — JSONB (иммутабельность документа). Подписанные титулы неизменяемы.

CREATE TABLE waybill (
    id                    UUID PRIMARY KEY,
    number                VARCHAR(20) UNIQUE,              -- национальный номер RR-YY-TT-NNNNNNN-K, присваивается в READY
    waybill_type          VARCHAR(20)  NOT NULL,           -- WB_BUS, WB_CAR, ...
    communication_type    VARCHAR(15)  NOT NULL DEFAULT 'URBAN',
    status                VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    med_passed            BOOLEAN      NOT NULL DEFAULT FALSE,
    tech_passed           BOOLEAN      NOT NULL DEFAULT FALSE,
    valid_from            TIMESTAMPTZ,
    valid_to              TIMESTAMPTZ,
    organization_rma      VARCHAR(10)  NOT NULL,
    vehicle_reg_number    VARCHAR(20)  NOT NULL,
    driver_rma            VARCHAR(10)  NOT NULL,
    second_driver_rma     VARCHAR(10),
    dispatcher_rma        VARCHAR(10),
    organization_snapshot JSONB,
    vehicle_snapshot      JSONB,
    driver_snapshot       JSONB,
    route                 VARCHAR(300),
    schedule              VARCHAR(100),
    odometer_exit         INTEGER,
    odometer_entry        INTEGER,
    special_mark          VARCHAR(1000),
    cancel_reason         VARCHAR(500),
    replaces_id           UUID,
    replaced_by_id        UUID,
    source                VARCHAR(20)  NOT NULL DEFAULT 'PORTAL', -- PORTAL | AGGREGATOR | API
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_waybill_vehicle_status ON waybill (vehicle_reg_number, status);
CREATE INDEX idx_waybill_driver_status ON waybill (driver_rma, status);
CREATE INDEX idx_waybill_org ON waybill (organization_rma);

CREATE TABLE waybill_title (
    id          UUID PRIMARY KEY,
    waybill_id  UUID        NOT NULL REFERENCES waybill (id),
    title_type  VARCHAR(12) NOT NULL, -- T1..T6, CORRECTION
    data        JSONB,
    signer_rma  VARCHAR(10) NOT NULL,
    signer_role VARCHAR(20) NOT NULL, -- DISPATCHER | DOCTOR | MECHANIC
    signature   VARCHAR(2000),        -- dev: хеш; prod: CAdES/XAdES
    signed_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_title_waybill ON waybill_title (waybill_id);

CREATE TABLE waybill_status_event (
    id          UUID PRIMARY KEY,
    waybill_id  UUID        NOT NULL REFERENCES waybill (id),
    from_status VARCHAR(20),
    to_status   VARCHAR(20) NOT NULL,
    actor       VARCHAR(100),
    reason      VARCHAR(500),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_status_event_waybill ON waybill_status_event (waybill_id);

CREATE SEQUENCE waybill_number_seq START 1;
