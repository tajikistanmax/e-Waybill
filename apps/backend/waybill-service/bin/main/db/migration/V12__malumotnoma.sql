-- Справки пассажирам (маълумотнома) — платный документ о стоимости проезда по маршруту.
-- Перенос подсистемы ИС «Роҳхат» (malumotnomas / rmalumotnomas / routemalumotnomas,
-- docs/spec/06 §6, формула цены — docs/spec/07 §7). В e-Waybill добавлена привязка
-- к организации (мультиарендность) — в оригинале справка организации не принадлежала.

-- Тарифицированные маршруты для справок (аналог routemalumotnomas: отдельные цены
-- по видам транспорта, не путать с таблицей route движка расчёта ПЛ).
CREATE TABLE malumotnoma_route (
    id           UUID PRIMARY KEY,
    name         VARCHAR(300) NOT NULL,
    distance_km  DOUBLE PRECISION NOT NULL DEFAULT 0,
    car_price    NUMERIC(12,2) NOT NULL DEFAULT 0,   -- легковой (вид 4)
    mbus_price   NUMERIC(12,2) NOT NULL DEFAULT 0,   -- микроавтобус (вид 3)
    bus_price    NUMERIC(12,2) NOT NULL DEFAULT 0,   -- автобус (вид 1)
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE malumotnoma (
    id                UUID PRIMARY KEY,
    fio               VARCHAR(300) NOT NULL,
    -- вид транспорта справки: 1 = автобус, 3 = микроавтобус, 4 = легковой (как в §7)
    transport_type_id SMALLINT NOT NULL,
    -- 1 = льготная (детская) справка → цена ×0.5
    age               SMALLINT NOT NULL DEFAULT 0,
    organization_rma  VARCHAR(10),
    issuer_rma        VARCHAR(10),
    issuer_name       VARCHAR(200),
    updater_name      VARCHAR(200),
    -- денормализованная цена на момент выдачи (для отчёта без пересчёта)
    price             NUMERIC(12,2) NOT NULL DEFAULT 0,
    route_summary     VARCHAR(1000),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_malumotnoma_created ON malumotnoma (created_at);
CREATE INDEX ix_malumotnoma_org ON malumotnoma (organization_rma);
CREATE INDEX ix_malumotnoma_issuer ON malumotnoma (issuer_rma);

CREATE TABLE malumotnoma_line (
    id                    UUID PRIMARY KEY,
    malumotnoma_id        UUID NOT NULL REFERENCES malumotnoma (id) ON DELETE CASCADE,
    malumotnoma_route_id  UUID NOT NULL REFERENCES malumotnoma_route (id),
    round_trip            BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX ix_malumotnoma_line_parent ON malumotnoma_line (malumotnoma_id);

-- Демо-маршруты для справок.
INSERT INTO malumotnoma_route (id, name, distance_km, car_price, mbus_price, bus_price) VALUES
    (gen_random_uuid(), 'Душанбе — Хуҷанд',       310, 250, 180, 120),
    (gen_random_uuid(), 'Душанбе — Бохтар',       100,  90,  70,  45),
    (gen_random_uuid(), 'Душанбе — Кӯлоб',        200, 160, 120,  80),
    (gen_random_uuid(), 'Хуҷанд — Исфара',         95,  80,  60,  40);
