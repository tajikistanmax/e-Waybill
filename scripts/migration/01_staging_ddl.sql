-- ============================================================================
-- Миграция боевых данных legacy «Роҳхат» (MySQL) -> e-Waybill (PostgreSQL)
-- Фаза 1 (справочники) + Фаза 2 (организации)
-- Файл 1/3: DDL промежуточных (staging) таблиц.
--
-- Все staging-колонки — text: данные приходят из mysql batch-режима как TSV,
-- реальные NULL приходят токеном 'NULL' и превращаются в SQL NULL опцией
-- COPY ... WITH (FORMAT text, NULL 'NULL') на этапе загрузки (см. orchestrator).
-- Скрипт идемпотентен: DROP + CREATE, поэтому повторный прогон безопасен.
-- Наши боевые/тестовые таблицы этот файл НЕ трогает.
-- ============================================================================

DROP TABLE IF EXISTS stg_brands;
CREATE TABLE stg_brands(
  type_id text, number text, name text, model text, capacity text, carrying text,
  cost_services text, net_weight text, fuel_100 text, fuel_100_dushanbe text,
  fuel_hour text, fuel_interior_heating text, tariff_rate text
);

DROP TABLE IF EXISTS stg_directions;
CREATE TABLE stg_directions(
  title text, number text, winter_coef_id text, mountain_coef_id text,
  in_city_coef_id text, checked text
);

DROP TABLE IF EXISTS stg_cargos;
CREATE TABLE stg_cargos(name text, type text, unit text, price text, class text);

DROP TABLE IF EXISTS stg_cities;
CREATE TABLE stg_cities(region_id text, code text, name text);

DROP TABLE IF EXISTS stg_regions;
CREATE TABLE stg_regions(code text, name text);

DROP TABLE IF EXISTS stg_route_types;
CREATE TABLE stg_route_types(number text, name text);

DROP TABLE IF EXISTS stg_drive_classes;
CREATE TABLE stg_drive_classes(class text, coef text);

DROP TABLE IF EXISTS stg_fuel_winter_coef;
CREATE TABLE stg_fuel_winter_coef(name text, period_from text, period_to text, coef text);

DROP TABLE IF EXISTS stg_city_coef;
CREATE TABLE stg_city_coef(name text, coef text);

DROP TABLE IF EXISTS stg_mountain_coef;
CREATE TABLE stg_mountain_coef(name text, coef text);

DROP TABLE IF EXISTS stg_used_coef;
CREATE TABLE stg_used_coef(year text, km text, coef text);

DROP TABLE IF EXISTS stg_ext_countries;
CREATE TABLE stg_ext_countries(id text, title text);

DROP TABLE IF EXISTS stg_ext_cities;
CREATE TABLE stg_ext_cities(country_id text, title text);

DROP TABLE IF EXISTS stg_companies;
CREATE TABLE stg_companies(
  id text, rma text, kpp text, name text, region_id text, address text, phone text,
  email text, name_head text, bank text, license_activity_from text,
  license_activity_to text, status_lock text, percent_income text, cat_1 text,
  cat_2 text, cat_3 text, license_number text, ownership_id text, type_company_id text,
  latitude text, longitude text, registration_certificate text, iktibos text, aai text,
  plan_pass_volume text, plan_pass_traffic text
);
