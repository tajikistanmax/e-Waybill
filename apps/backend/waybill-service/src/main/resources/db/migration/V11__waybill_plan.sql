-- Плановые показатели перевозок для сводного регионального отчёта Минтранса
-- (перенос таблицы waybill_plans ИС «Роҳхат», docs/spec/07-calculations.md §6).
-- Годовой план объёма и оборота — по предприятию (organization_rma) либо
-- республиканский (organization_rma IS NULL, region_id IS NULL).
CREATE TABLE waybill_plan (
    id                UUID PRIMARY KEY,
    organization_rma  VARCHAR(10),
    region_id         SMALLINT,
    plan_year         INT  NOT NULL,
    -- PASSENGER (автобус/троллейбус/микроавтобус) | TAXI | CARGO
    plan_kind         VARCHAR(16) NOT NULL,
    -- план объёма перевозок: тыс. пассажиров либо тыс. тонн
    volume_thousand   DOUBLE PRECISION NOT NULL DEFAULT 0,
    -- план оборота: млн пасс-км либо млн т-км
    rotation_million  DOUBLE PRECISION NOT NULL DEFAULT 0,
    note              VARCHAR(300),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_waybill_plan_scope
    ON waybill_plan (COALESCE(organization_rma, '*'), plan_year, plan_kind);
CREATE INDEX ix_waybill_plan_year ON waybill_plan (plan_year, plan_kind);

-- Демо-план на текущий и прошлый год для стендовых организаций.
INSERT INTO waybill_plan (id, organization_rma, region_id, plan_year, plan_kind, volume_thousand, rotation_million, note) VALUES
    (gen_random_uuid(), '100002000', 4, EXTRACT(YEAR FROM now())::int,     'PASSENGER', 1200, 18, 'демо-план ҶСК Троллейбус'),
    (gen_random_uuid(), '100002000', 4, EXTRACT(YEAR FROM now())::int - 1, 'PASSENGER', 1100, 16, 'демо-план прошлого года'),
    (gen_random_uuid(), '100001000', 1, EXTRACT(YEAR FROM now())::int,     'PASSENGER',  900, 12, 'демо-план'),
    (gen_random_uuid(), '100001000', 1, EXTRACT(YEAR FROM now())::int - 1, 'PASSENGER',  850, 11, 'демо-план прошлого года'),
    (gen_random_uuid(), NULL,        NULL, EXTRACT(YEAR FROM now())::int,   'PASSENGER', 5000, 80, 'республиканский план'),
    (gen_random_uuid(), NULL,        NULL, EXTRACT(YEAR FROM now())::int-1, 'PASSENGER', 4700, 74, 'республиканский план прошлого года');
