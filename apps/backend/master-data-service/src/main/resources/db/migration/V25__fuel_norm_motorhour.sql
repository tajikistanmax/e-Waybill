-- Топливо спецтехники нормируется по МОТОЧАСАМ (л/моточас), а не по километражу (л/100км).
-- Добавляем единицу нормирования fuel_norm.unit (KM по умолчанию | MOTORHOUR) и национальную
-- норму спецтехники. У спецтехники нет фиксированного transport_type (allowedTransportTypes=null),
-- поэтому норму хранит служебная строка (transport_type = 9 — маркер «спецтехника», совместим с
-- частичным уникальным индексом uq_fuel_norm_type_default по transport_type). Расчёт для WB_SPECIAL
-- берёт норму по unit='MOTORHOUR': normLiters = норма × моточасы × коэффициенты (без деления на 100).
alter table fuel_norm add column if not exists unit varchar(10) not null default 'KM';

insert into fuel_norm (id, transport_type, brand, base_norm, unit) values
  ('11111111-0000-0000-0000-000000000009', 9, NULL, 12.00, 'MOTORHOUR');
