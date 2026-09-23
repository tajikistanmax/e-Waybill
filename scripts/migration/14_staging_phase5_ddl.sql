-- ============================================================================
-- ФАЗА 5 — исторические путевые листы (архив, read-only). Файл 1/2: staging DDL.
-- Целевая БД: waybill (НЕ masterdata). Загружается оркестратором через COPY.
--
-- ОБОБЩЁННЫЙ staging под ВСЕ типы ПЛ (3cs/1as/1ads/2bs/5bbms): экспорт-запрос
-- каждой legacy-таблицы приводит строки к единой «шапке» (недостающие поля = NULL).
-- Всё text — касты и валидация в файле 15. Натуральные ключи (rma/госномер/маршрут/
-- водитель) резолвятся ЗАРАНЕЕ, на стороне MySQL (все таблицы в одной БД).
-- ============================================================================

SET client_encoding TO 'UTF8';

DROP TABLE IF EXISTS stg_wb5;
CREATE TABLE stg_wb5 (
  legacy_id           text,   -- 1  legacy PK (bigint), гарант уникальности номера
  src_code            text,   -- 2  '3C'|'1A'|'1D'|'2B'|'5F' — префикс номера/тип источника
  waybill_type        text,   -- 3  WB_TAXI|WB_CAR|WB_MINIBUS|WB_BUS|WB_TROLLEYBUS|WB_TRUCK|WB_TRUCK_INTL
  communication_type  text,   -- 4  URBAN|SUBURBAN|... (если известно), иначе NULL->URBAN
  org_rma             text,   -- 5  companies.rma (обязателен: сироты отброшены в экспорте)
  org_name            text,   -- 6  companies.name (для снапшота)
  veh_reg             text,   -- 7  parkings.registration_number
  veh_brand           text,   -- 8  brands.name / parkings.brand_name
  driver_rma          text,   -- 9  drivers.rma (осн. водитель)
  driver_name         text,   -- 10 drivers.full_name
  second_driver_rma   text,   -- 11 второй водитель (только 5bbms), иначе NULL
  second_driver_name  text,   -- 12
  route_text          text,   -- 13 "name_a - name_b" по route_id
  number              text,   -- 14 legacy № (int) — в type_data.legacyNumber
  exit_date           text,   -- 15 выезд -> valid_from
  entry_date          text,   -- 16 возврат -> valid_to
  odo_exit            text,   -- 17 показания при выезде
  odo_entry           text,   -- 18 показания при возврате
  schedule            text,   -- 19 график
  special_mark        text,   -- 20 особые отметки
  created_at          text,   -- 21 legacy created_at -> created_at
  type_service        text,   -- 22 3cs: 1=такси/2=маршрут/3=почасовой (в type_data)
  -- 23..29 (Ф5b, 23.09.2026): поля, которые расчёт отчёта берёт из снимков ПЛ. Legacy берёт их
  -- «вживую» у связанных записей; архивный снимок должен их нести, иначе пассажирооборот и
  -- заработок водителя архивного листа = 0 (находка 11 AUDIT.md).
  veh_capacity        text,   -- 23 вместимость: brands.capacity (такси «по счётчику» — parkings.capacity)
  org_percent_income  text,   -- 24 companies.percent_income — доля дохода (заработок водителя)
  org_cat1            text,   -- 25 companies.cat_1 — надбавка за класс 1
  org_cat2            text,   -- 26 companies.cat_2
  org_cat3            text,   -- 27 companies.cat_3
  org_region_id       text,   -- 28 companies.region_id (1 = Душанбе: пробег автобуса по спидометру)
  driver_degree       text    -- 29 drivers.degree — класс водителя
);
