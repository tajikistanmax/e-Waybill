-- GPS-события Smart-city (MIGRATION.md 9.7 / 8.9 / 12.12 — legacy таблица gps_data, GpsDataController::store):
-- камеры/посты города фиксируют заезд/выезд ТС на маршрут (с направлением А/Б) и в/из предприятия
-- (с дистанцией). Одна строка = одно событие; привязка к ПЛ дня (Т 1-АД), снимки водителя/организации/маршрута.

CREATE TABLE gps_event (
    id                 UUID PRIMARY KEY,
    waybill_id         UUID         NOT NULL,   -- ПЛ дня, к которому отнесено событие (legacy waybill_id)
    organization_rma   VARCHAR(20)  NOT NULL,   -- организация ПЛ (legacy company_id) — фильтр журнала
    organization_name  VARCHAR(300),            -- снимок названия для журнала
    vehicle_reg_number VARCHAR(20)  NOT NULL,   -- госномер ТС (канонизирован: trim + upper; legacy parking_id)
    driver_rma         VARCHAR(20),             -- водитель ПЛ (legacy driver_id)
    driver_name        VARCHAR(300),            -- снимок ФИО
    route              VARCHAR(200),            -- маршрут ПЛ (legacy route_id → номер/описание)
    state              VARCHAR(24)  NOT NULL,   -- ENTER_INTO_ROUTE | EXIT_FROM_ROUTE | ENTER_INTO_COMPANY | EXIT_FROM_COMPANY
    direction          VARCHAR(1),              -- A | B — только для маршрутных состояний
    distance_km        NUMERIC(10,2),           -- дистанция — обязательна для ENTER_INTO_COMPANY
    event_time         TIMESTAMPTZ  NOT NULL,   -- момент фиксации (legacy time — только время суток)
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Правило «повтор не чаще N минут»: последнее событие ТС того же состояния (и направления).
CREATE INDEX idx_gps_event_vehicle_state ON gps_event (vehicle_reg_number, state, event_time DESC);
-- Журнал по организации за период.
CREATE INDEX idx_gps_event_org_time ON gps_event (organization_rma, event_time DESC);
-- События по ПЛ.
CREATE INDEX idx_gps_event_waybill ON gps_event (waybill_id, event_time);
