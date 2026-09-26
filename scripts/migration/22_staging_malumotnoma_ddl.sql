-- ============================================================================
-- СПРАВКИ (маълумотнома) — staging в БД waybill. Файл 1/2 (DROP + CREATE, повторяемо).
-- routemalumotnomas -> stg_mlm_route, malumotnomas (+ имена кассиров) -> stg_mlm,
-- rmalumotnomas -> stg_mlm_line. Все даты — текстом, как их отдаёт mysql -B (UTC).
-- ============================================================================
SET client_encoding TO 'UTF8';

DROP TABLE IF EXISTS stg_mlm_route;
CREATE TABLE stg_mlm_route (
  id         integer PRIMARY KEY,
  name       text,
  distance   integer,
  car_price  numeric,
  mbus_price numeric,
  bus_price  numeric
);

DROP TABLE IF EXISTS stg_mlm;
CREATE TABLE stg_mlm (
  id                bigint PRIMARY KEY,
  fio               text,
  create_user       text,
  update_user       text,
  created_at        text,
  updated_at        text,
  deleted_at        text,
  age               integer,
  transport_type_id integer
);

DROP TABLE IF EXISTS stg_mlm_line;
CREATE TABLE stg_mlm_line (
  id             bigint PRIMARY KEY,
  route_id       integer,
  round_trip     integer,
  malumotnoma_id bigint
);
