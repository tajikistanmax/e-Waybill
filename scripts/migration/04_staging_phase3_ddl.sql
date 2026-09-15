-- ============================================================================
-- Миграция боевых данных legacy «Роҳхат» (MySQL) -> e-Waybill (PostgreSQL)
-- ФАЗА 3 — реестры (parkings->vehicle, drivers->driver, employees->employee,
-- routes->route). Файл 1/2: DDL промежуточных (staging) таблиц.
--
-- Все staging-колонки — text (данные приходят из mysql batch как TSV; токен
-- 'NULL' -> SQL NULL опцией COPY ... WITH (FORMAT text, NULL 'NULL')).
-- Скрипт идемпотентен: DROP + CREATE. Наши боевые таблицы этот файл НЕ трогает.
-- ============================================================================

-- Карта company_id -> rma (все компании, включая soft-deleted: сам факт наличия
-- организации в target решает, сирота запись или нет).
DROP TABLE IF EXISTS stg_companies_map;
CREATE TABLE stg_companies_map(id text, rma text);

-- Карта brand_id -> name (fallback, если в parkings пусто brand_name).
DROP TABLE IF EXISTS stg_brands_map;
CREATE TABLE stg_brands_map(id text, name text);

-- parkings -> vehicle (только deleted_at IS NULL).
DROP TABLE IF EXISTS stg_parkings;
CREATE TABLE stg_parkings(
  id text, number text, registration_number text, brand_id text, brand_name text,
  capacity text, carrying text,
  number_ydak text, brand_ydak text, carrying_ydak text, weight_ydak text,
  number_ydak_2 text, brand_ydak_2 text, carrying_ydak_2 text, weight_ydak_2 text,
  tech_inspection_number text, tech_inspection_date_to text, certificate_number text,
  expire_checklist_number text, expire_checklist_date_to text,
  expire_checklist_itl_number text, expire_checklist_itl_date_to text,
  year_manufacture text, vincode text, company_id text, air_conditioner text,
  transport_type_id text
);

-- drivers -> driver (только deleted_at IS NULL).
DROP TABLE IF EXISTS stg_drivers;
CREATE TABLE stg_drivers(
  id text, full_name text, number text, category text, license text, passport text,
  degree text, med_cert_number text, med_cert_valid_date text, rma text,
  power_attorney text, visa_valid_date text, address text, phone text, email text,
  company_id text, contract_number text
);

-- employees -> employee (только deleted_at IS NULL).
DROP TABLE IF EXISTS stg_employees;
CREATE TABLE stg_employees(
  id text, type text, number text, name text, company_id text, address text,
  phone text, rma text
);

-- routes -> route.
DROP TABLE IF EXISTS stg_routes;
CREATE TABLE stg_routes(
  id text, number text, type_id text, name_a text, name_b text,
  distance_a text, distance_b text, begin_path_a text, begin_path_b text,
  planned_lap text, coe_use_capacity text, average_length_pass_seat text,
  region_id text, station_coef text, road_quality text, transport_type_id text,
  company_id text, additional_fuel_100 text, additional_fuel text, cond_fuel text,
  heating_fuel text, excluding_coef text
);
