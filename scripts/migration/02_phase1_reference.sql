-- ============================================================================
-- Файл 2/3: ФАЗА 1 — справочники. Трансформация staging -> целевые таблицы.
-- Предполагается, что staging уже загружен (orchestrator сделал COPY).
-- Всё идемпотентно: INSERT ... ON CONFLICT DO NOTHING.
-- Наши уже засеянные справочники (страны 218, регионы 7, route_types 5,
-- external_city 103 и т.д.) НЕ перезаписываются — только добавляем недостающее.
-- НИКАКИХ DELETE/TRUNCATE/UPDATE наших данных.
-- ============================================================================

-- ---------- brands -> brand -------------------------------------------------
-- Ключ идемпотентности: uq_brand_name_model (lower(name), lower(model)).
INSERT INTO brand(type_id, number, name, model, capacity, carrying, cost_services,
                  net_weight, fuel_100, fuel_100_dushanbe, fuel_hour,
                  fuel_interior_heating, tariff_rate)
SELECT
  COALESCE(NULLIF(type_id,'')::bigint, 0),
  NULLIF(number,''),
  name,
  COALESCE(NULLIF(model,''), ''),
  NULLIF(capacity,'')::int,
  NULLIF(carrying,'')::double precision,
  NULLIF(cost_services,'')::double precision,
  COALESCE(NULLIF(net_weight,'')::numeric, 0),
  NULLIF(fuel_100,''),
  NULLIF(fuel_100_dushanbe,''),
  NULLIF(fuel_hour,''),
  NULLIF(fuel_interior_heating,'')::double precision,
  NULLIF(tariff_rate,'')::double precision
FROM stg_brands
WHERE btrim(COALESCE(name,'')) <> ''
ON CONFLICT (lower(name), lower(model)) DO NOTHING;

-- ---------- cargos -> cargo -------------------------------------------------
-- cargo.id БЕЗ default -> генерим uuid. Ключ: uq_cargo_name (lower(name)).
INSERT INTO cargo(id, name, type, unit, price, cargo_class)
SELECT gen_random_uuid(), name, NULLIF(type,''), NULLIF(unit,''),
       NULLIF(price,'')::numeric, NULLIF(class,'')::smallint
FROM stg_cargos
WHERE btrim(COALESCE(name,'')) <> ''
ON CONFLICT (lower(name)) DO NOTHING;

-- ---------- cities -> city --------------------------------------------------
-- Ключ: uq_city_region_code (region_id, code). CHECK region_id 1..7.
INSERT INTO city(region_id, code, name)
SELECT region_id::smallint, code, name
FROM stg_cities
WHERE NULLIF(region_id,'') IS NOT NULL
  AND region_id::int BETWEEN 1 AND 7
  AND btrim(COALESCE(code,'')) <> ''
ON CONFLICT (region_id, code) DO NOTHING;

-- ---------- directions -> direction -----------------------------------------
-- Ключ: uq_direction_title (lower(title)).
-- ВАЖНО: winter/mountain/in_city_coef_id в legacy — это id-ссылки на коэф-таблицы
-- legacy. У нас коэф-таблицы с generated identity, id НЕ совпадают, поэтому
-- ссылки НЕ переносим (ставим NULL), чтобы не создать «висящие» ссылки.
-- На практике целевая direction уже засеяна (>= legacy), вставок обычно 0.
INSERT INTO direction(title, number, winter_coef_id, mountain_coef_id,
                      in_city_coef_id, checked)
SELECT title, NULLIF(number,'')::int, NULL, NULL, NULL,
       COALESCE(NULLIF(checked,'')::int, 0) = 1
FROM stg_directions
WHERE btrim(COALESCE(title,'')) <> ''
ON CONFLICT (lower(title)) DO NOTHING;

-- ---------- regions -> region (reconcile) -----------------------------------
-- Ключ: uq_region_code (code). DO NOTHING -> засеянные русские названия целы.
INSERT INTO region(code, name_ru)
SELECT code::smallint, name
FROM stg_regions
WHERE NULLIF(code,'') IS NOT NULL AND code::int BETWEEN 1 AND 7
  AND btrim(COALESCE(name,'')) <> ''
ON CONFLICT (code) DO NOTHING;

-- ---------- route_types -> route_type (reconcile) ---------------------------
-- legacy.number -> code. Ключ: uq_route_type_code (code).
INSERT INTO route_type(code, name_ru)
SELECT number::smallint, name
FROM stg_route_types
WHERE NULLIF(number,'') IS NOT NULL AND btrim(COALESCE(name,'')) <> ''
ON CONFLICT (code) DO NOTHING;

-- ---------- drive_classes -> drive_class ------------------------------------
-- Ключ: uq_drive_class_class (lower(class)).
INSERT INTO drive_class(class, coef)
SELECT class, NULLIF(coef,'')::smallint
FROM stg_drive_classes
WHERE btrim(COALESCE(class,'')) <> ''
ON CONFLICT (lower(class)) DO NOTHING;

-- ---------- fuel_winter_coef -> fuel_winter_coef ----------------------------
-- Ключ: uq_fuel_winter_coef_name (lower(name)).
INSERT INTO fuel_winter_coef(name, period_from, period_to, coef)
SELECT name, NULLIF(period_from,'')::date, NULLIF(period_to,'')::date,
       NULLIF(coef,'')::smallint
FROM stg_fuel_winter_coef
WHERE btrim(COALESCE(name,'')) <> ''
ON CONFLICT (lower(name)) DO NOTHING;

-- ---------- city_coef -> city_coef ------------------------------------------
INSERT INTO city_coef(name, coef)
SELECT name, NULLIF(coef,'')::smallint
FROM stg_city_coef
WHERE btrim(COALESCE(name,'')) <> ''
ON CONFLICT (lower(name)) DO NOTHING;

-- ---------- mountain_coef -> mountain_coef ----------------------------------
INSERT INTO mountain_coef(name, coef)
SELECT name, NULLIF(coef,'')::smallint
FROM stg_mountain_coef
WHERE btrim(COALESCE(name,'')) <> ''
ON CONFLICT (lower(name)) DO NOTHING;

-- ---------- used_coef -> used_coef ------------------------------------------
-- Ключ: uq_used_coef_year_km (year, km).
INSERT INTO used_coef(year, km, coef)
SELECT NULLIF(year,'')::smallint, NULLIF(km,'')::int, NULLIF(coef,'')::smallint
FROM stg_used_coef
WHERE NULLIF(year,'') IS NOT NULL AND NULLIF(km,'') IS NOT NULL
ON CONFLICT (year, km) DO NOTHING;

-- ---------- external_cities -> external_city (полные ~19k) -------------------
-- country_id (legacy) -> country_code (ISO2 из classifier COUNTRY).
-- Сопоставление стран по имени (name_ru ИЛИ name_tj) — покрывает 217/218 стран.
-- Единственная несовпавшая страна — Кот-д'Ивуар (legacy id 95, отличается символ
-- апострофа) — задаём вручную код 'CI'. name_ru и name_tj = legacy title
-- (в legacy одно имя-строка; повторяем в оба поля, как в засеянных 103).
-- Ключ идемпотентности: uq_external_city (country_code, name_ru).
WITH alias(legacy_id, code) AS (VALUES ('95','CI')),
cmap AS (
  SELECT ec.id AS legacy_id,
         COALESCE(
           (SELECT cl.code FROM classifier cl
             WHERE cl.category='COUNTRY'
               AND (lower(btrim(cl.name_ru)) = lower(btrim(ec.title))
                 OR lower(btrim(cl.name_tj)) = lower(btrim(ec.title)))
             LIMIT 1),
           (SELECT a.code FROM alias a WHERE a.legacy_id = ec.id)
         ) AS country_code
  FROM stg_ext_countries ec
)
INSERT INTO external_city(country_code, name_ru, name_tj)
SELECT m.country_code, x.title, x.title
FROM stg_ext_cities x
JOIN cmap m ON m.legacy_id = x.country_id
WHERE m.country_code IS NOT NULL
  AND btrim(COALESCE(x.title,'')) <> ''
ON CONFLICT (country_code, name_ru) DO NOTHING;
