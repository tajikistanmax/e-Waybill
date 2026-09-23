-- ============================================================================
-- ФАЗА 1b (нормативы марок) + ФАЗА 3c (недостающие маршруты).
-- Файл 1/3: DDL промежуточных (staging) таблиц. DROP + CREATE — повторный прогон безопасен.
-- Все колонки text: данные приходят из mysql batch (-N -B) как TSV, NULL — токеном 'NULL'
-- (COPY ... WITH (FORMAT text, NULL 'NULL')). Целевые таблицы этот файл НЕ трогает.
-- ============================================================================

-- brands legacy: id — для детерминированного выбора среди дублей (имя + модель).
DROP TABLE IF EXISTS stg_brand_norms;
CREATE TABLE stg_brand_norms(
  id text, name text, model text, number text,
  fuel_100 text, fuel_100_dushanbe text, fuel_hour text, fuel_interior_heating text
);

-- routes legacy целиком (с id и rma организации — для поиска непереносённых).
DROP TABLE IF EXISTS stg_routes_3c;
CREATE TABLE stg_routes_3c(
  id text, number text, type_id text, name_a text, name_b text,
  distance_a text, distance_b text, begin_path_a text, begin_path_b text,
  planned_lap text, coe_use_capacity text, average_length_pass_seat text,
  region_id text, station_coef text, road_quality text, transport_type_id text,
  company_rma text, additional_fuel_100 text, additional_fuel text,
  cond_fuel text, heating_fuel text, excluding_coef text,
  -- коэффициенты legacy: горный/городской — ЗНАЧЕНИЯ (helpers.php getCoef складывает их
  -- в K как есть), зимний — ссылка; переносим по имени периода (id у нас другие).
  winter_name text, mountain_coef_id text, in_city_coef_id text
);
