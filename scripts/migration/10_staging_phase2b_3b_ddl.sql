-- ============================================================================
-- Догрузка миграции: ФАЗА 2b (организации без РМА) + ФАЗА 3b (их ТС/водители/
-- сотрудники/маршруты-сироты). Файл 1/3: DDL промежуточных (staging) таблиц.
--
-- Контекст: 176 companies с пустым rma не попали в Ф2 (organization.rma NOT NULL),
-- из-за чего их parkings/drivers/employees/routes были пропущены в Ф3 как сироты.
-- Здесь грузим ТОЛЬКО подмножество сирот (JOIN к companies с пустым rma).
--
-- Все staging-колонки — text (данные приходят из mysql batch как TSV; токен
-- 'NULL' -> SQL NULL опцией COPY ... WITH (FORMAT text, NULL 'NULL')).
-- Скрипт идемпотентен: DROP + CREATE. Наши боевые таблицы этот файл НЕ трогает.
-- ============================================================================

-- Орфан-компании (deleted_at IS NULL AND (rma IS NULL OR rma='')). Набор полей —
-- как в Ф2 (stg_companies), плюс id: синтетический ключ orga = 'MIG'||id.
DROP TABLE IF EXISTS stg_b_companies;
CREATE TABLE stg_b_companies(
  id text, rma text, kpp text, name text, region_id text, address text, phone text,
  email text, name_head text, bank text, license_activity_from text,
  license_activity_to text, status_lock text, percent_income text, cat_1 text,
  cat_2 text, cat_3 text, license_number text, ownership_id text, type_company_id text,
  latitude text, longitude text, registration_certificate text, iktibos text, aai text,
  plan_pass_volume text, plan_pass_traffic text
);

-- Карта brand_id -> name (fallback, если в parkings пусто brand_name).
DROP TABLE IF EXISTS stg_b_brands_map;
CREATE TABLE stg_b_brands_map(id text, name text);

-- parkings орфан-компаний (deleted_at IS NULL). Набор полей — как в Ф3.
DROP TABLE IF EXISTS stg_b_parkings;
CREATE TABLE stg_b_parkings(
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

-- drivers орфан-компаний (deleted_at IS NULL). Набор полей — как в Ф3.
DROP TABLE IF EXISTS stg_b_drivers;
CREATE TABLE stg_b_drivers(
  id text, full_name text, number text, category text, license text, passport text,
  degree text, med_cert_number text, med_cert_valid_date text, rma text,
  power_attorney text, visa_valid_date text, address text, phone text, email text,
  company_id text, contract_number text
);

-- employees орфан-компаний (deleted_at IS NULL).
DROP TABLE IF EXISTS stg_b_employees;
CREATE TABLE stg_b_employees(
  id text, type text, number text, name text, company_id text, address text,
  phone text, rma text
);

-- routes орфан-компаний.
DROP TABLE IF EXISTS stg_b_routes;
CREATE TABLE stg_b_routes(
  id text, number text, type_id text, name_a text, name_b text,
  distance_a text, distance_b text, begin_path_a text, begin_path_b text,
  planned_lap text, coe_use_capacity text, average_length_pass_seat text,
  region_id text, station_coef text, road_quality text, transport_type_id text,
  company_id text, additional_fuel_100 text, additional_fuel text, cond_fuel text,
  heating_fuel text, excluding_coef text
);
