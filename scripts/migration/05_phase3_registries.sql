-- ============================================================================
-- ФАЗА 3 — реестры. Файл 2/2: трансформация staging -> целевые таблицы.
-- Предполагается, что staging уже загружен (orchestrator сделал COPY).
-- Всё идемпотентно: INSERT ... ON CONFLICT DO NOTHING. НИКАКИХ DELETE/UPDATE/TRUNCATE.
--
-- РЕЗОЛВ ОРГАНИЗАЦИИ (общий для vehicle/driver/employee/route):
--   parkings.company_id -> companies.id -> companies.rma -> organization.rma -> id.
--   Карта company_id->rma в stg_companies_map. Сироты (организация не мигрирована
--   или rma пустой) — ПРОПУСКАЕМ (WHERE org найден). vehicle/driver/employee хранят
--   organization_id (UUID); client/route — organization_rma (строка).
--
-- ЗАЩИТНЫЕ КАСТЫ:
--   * даты — только строгий шаблон YYYY-MM-DD с валид. мес/днём и годом != 0000
--     (в legacy встречается '0000-00-00', его нельзя привести к ::date);
--   * числа — регэксп-проверка перед ::cast (в staging всё text).
-- ============================================================================

SET client_encoding TO 'UTF8';


-- ############################################################################
-- 1) parkings -> vehicle  (~107k active)
-- ############################################################################
-- Дедуп по registration_number (target: vehicle_registration_number_key UNIQUE):
--   DISTINCT ON (upper(btrim(registration_number))), берём запись с макс. legacy id.
-- vincode: у target есть ЧАСТИЧНЫЙ UNIQUE-индекс uq_vehicle_vincode на
--   upper(btrim(vincode)) WHERE vincode непустой. В legacy ~5238 групп дублей vincode —
--   поэтому оставляем vincode только у ПЕРВОЙ записи в группе (row_number=1) и только
--   если такого vincode ещё нет в target; иначе NULL (ON CONFLICT ловит лишь один
--   констрейнт — registration_number, поэтому дубли vincode гасим заранее).
-- year_manufacture в legacy — DATE ('2019-01-01'); берём год.
-- parking_number target varchar(4): legacy number (int) кладём только если влезает.
-- odometer NOT NULL -> дефолт таблицы 0 (колонку не указываем).
INSERT INTO vehicle(
  id, registration_number, organization_id, transport_type, brand, parking_number,
  capacity, carrying, vincode, year_manufacture, tech_inspection_valid_to,
  tech_inspection_number, certificate_number, control_card_number, control_card_valid_to,
  intl_control_card_number, intl_control_card_valid_to, air_conditioner,
  trailer1_number, trailer1_brand, trailer1_carrying, trailer1_weight,
  trailer2_number, trailer2_brand, trailer2_carrying, trailer2_weight,
  blocked, source, created_at, updated_at)
SELECT
  gen_random_uuid(),
  left(btrim(d.registration_number), 20),
  d.org_id,
  COALESCE(CASE WHEN d.transport_type_id ~ '^[0-9]+$' THEN d.transport_type_id::smallint END, 0),
  left(COALESCE(NULLIF(btrim(d.brand_name),''), NULLIF(btrim(d.bmap_name),'')), 200),
  CASE WHEN d.number ~ '^[0-9]{1,4}$' THEN d.number END,
  CASE WHEN d.capacity ~ '^[0-9]+$' THEN d.capacity::int END,
  CASE WHEN d.carrying ~ '^-?[0-9]+(\.[0-9]+)?$' AND abs(d.carrying::numeric) < 100000000
       THEN round(d.carrying::numeric, 2) END,
  -- vincode: только уникальный в наборе и отсутствующий в target
  CASE WHEN btrim(COALESCE(d.vincode,'')) <> '' AND d.vin_rn = 1
            AND NOT EXISTS (SELECT 1 FROM vehicle v
                            WHERE upper(btrim(v.vincode)) = upper(btrim(d.vincode)))
       THEN left(d.vincode, 50) END,
  CASE WHEN d.year_manufacture ~ '^[1-9][0-9]{3}-' THEN substr(d.year_manufacture,1,4)::smallint END,
  CASE WHEN d.tech_inspection_date_to ~ '^[1-9][0-9]{3}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])'
       THEN substr(d.tech_inspection_date_to,1,10)::date END,
  left(NULLIF(btrim(d.tech_inspection_number),''), 50),
  left(NULLIF(btrim(d.certificate_number),''), 50),
  left(NULLIF(btrim(d.expire_checklist_number),''), 50),
  CASE WHEN d.expire_checklist_date_to ~ '^[1-9][0-9]{3}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])'
       THEN substr(d.expire_checklist_date_to,1,10)::date END,
  left(NULLIF(btrim(d.expire_checklist_itl_number),''), 50),
  CASE WHEN d.expire_checklist_itl_date_to ~ '^[1-9][0-9]{3}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])'
       THEN substr(d.expire_checklist_itl_date_to,1,10)::date END,
  CASE WHEN d.air_conditioner ~ '^[0-9]+$' AND d.air_conditioner::int BETWEEN 0 AND 100
       THEN d.air_conditioner::int END,
  left(NULLIF(btrim(d.number_ydak),''), 20),
  left(NULLIF(btrim(d.brand_ydak),''), 200),
  CASE WHEN d.carrying_ydak ~ '^-?[0-9]+(\.[0-9]+)?$' AND abs(d.carrying_ydak::numeric) < 100000000
       THEN round(d.carrying_ydak::numeric,2) END,
  CASE WHEN d.weight_ydak ~ '^-?[0-9]+(\.[0-9]+)?$' AND abs(d.weight_ydak::numeric) < 100000000
       THEN round(d.weight_ydak::numeric,2) END,
  left(NULLIF(btrim(d.number_ydak_2),''), 20),
  left(NULLIF(btrim(d.brand_ydak_2),''), 200),
  CASE WHEN d.carrying_ydak_2 ~ '^-?[0-9]+(\.[0-9]+)?$' AND abs(d.carrying_ydak_2::numeric) < 100000000
       THEN round(d.carrying_ydak_2::numeric,2) END,
  CASE WHEN d.weight_ydak_2 ~ '^-?[0-9]+(\.[0-9]+)?$' AND abs(d.weight_ydak_2::numeric) < 100000000
       THEN round(d.weight_ydak_2::numeric,2) END,
  false,
  'MIGRATED',
  now(), now()
FROM (
  SELECT dd.*,
         CASE WHEN btrim(COALESCE(dd.vincode,'')) <> ''
              THEN row_number() OVER (PARTITION BY upper(btrim(dd.vincode)) ORDER BY dd.id::bigint DESC)
         END AS vin_rn
  FROM (
    SELECT DISTINCT ON (upper(btrim(p.registration_number)))
           p.*, o.id AS org_id, bm.name AS bmap_name
    FROM stg_parkings p
    LEFT JOIN stg_companies_map cm ON cm.id = p.company_id
    LEFT JOIN organization o ON o.rma = btrim(cm.rma) AND btrim(COALESCE(cm.rma,'')) <> ''
    LEFT JOIN stg_brands_map bm ON bm.id = p.brand_id
    WHERE btrim(COALESCE(p.registration_number,'')) <> ''
      AND o.id IS NOT NULL                          -- сирот пропускаем
    ORDER BY upper(btrim(p.registration_number)), p.id::bigint DESC
  ) dd
) d
ON CONFLICT (registration_number) DO NOTHING;


-- ############################################################################
-- 2) drivers -> driver  (~121k valid rma, ~68k distinct)
-- ############################################################################
-- Дедуп по rma (target: driver_rma_key UNIQUE, varchar(10)):
--   отбрасываем rma пустые и длиннее 10; DISTINCT ON (upper(btrim(rma))), макс. id.
-- Мед-данные — только справочные поля водителя (med_cert_number/valid_to),
-- показателей осмотров здесь нет (их шифрование — не про эту таблицу).
INSERT INTO driver(
  id, rma, organization_id, tab_number, full_name, license_number, license_categories,
  degree, med_cert_number, med_cert_valid_to, phone, passport, address, email,
  power_attorney, visa_valid_to, contract_number, suspended, source, created_at, updated_at)
SELECT
  gen_random_uuid(),
  left(btrim(d.rma), 10),
  d.org_id,
  left(NULLIF(btrim(d.number),''), 10),
  left(btrim(d.full_name), 300),
  left(NULLIF(btrim(d.license),''), 50),
  left(NULLIF(btrim(d.category),''), 30),
  CASE WHEN d.degree ~ '^[0-9]+$' THEN d.degree::smallint END,
  left(NULLIF(btrim(d.med_cert_number),''), 50),
  CASE WHEN d.med_cert_valid_date ~ '^[1-9][0-9]{3}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])'
       THEN substr(d.med_cert_valid_date,1,10)::date END,
  left(NULLIF(btrim(d.phone),''), 50),
  left(NULLIF(btrim(d.passport),''), 50),
  left(NULLIF(btrim(d.address),''), 300),
  left(NULLIF(btrim(d.email),''), 150),
  left(NULLIF(btrim(d.power_attorney),''), 100),
  CASE WHEN d.visa_valid_date ~ '^[1-9][0-9]{3}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])'
       THEN substr(d.visa_valid_date,1,10)::date END,
  left(NULLIF(btrim(d.contract_number),''), 50),
  false,
  'MIGRATED',
  now(), now()
FROM (
  SELECT DISTINCT ON (upper(btrim(dr.rma)))
         dr.*, o.id AS org_id
  FROM stg_drivers dr
  LEFT JOIN stg_companies_map cm ON cm.id = dr.company_id
  LEFT JOIN organization o ON o.rma = btrim(cm.rma) AND btrim(COALESCE(cm.rma,'')) <> ''
  WHERE btrim(COALESCE(dr.rma,'')) <> ''
    AND char_length(btrim(dr.rma)) <= 10
    AND btrim(COALESCE(dr.full_name,'')) <> ''
    AND o.id IS NOT NULL                            -- сирот пропускаем
  ORDER BY upper(btrim(dr.rma)), dr.id::bigint DESC
) d
ON CONFLICT (rma) DO NOTHING;


-- ############################################################################
-- 3) employees -> employee  (~484, но rma есть лишь у части)
-- ############################################################################
-- target employee.rma NOT NULL UNIQUE varchar(10) -> без rma запись не вставить.
-- Пропускаем employees с пустым/длинным rma (посчитано отдельно). type NOT NULL.
INSERT INTO employee(
  id, rma, organization_id, tab_number, name, type, phone, address,
  source, created_at, updated_at)
SELECT
  gen_random_uuid(),
  left(btrim(e.rma), 10),
  e.org_id,
  left(NULLIF(btrim(e.number),''), 10),
  left(btrim(e.name), 300),
  COALESCE(CASE WHEN e.type ~ '^[0-9]+$' THEN e.type::smallint END, 0),
  left(NULLIF(btrim(e.phone),''), 50),
  left(NULLIF(btrim(e.address),''), 300),
  'MIGRATED',
  now(), now()
FROM (
  SELECT DISTINCT ON (upper(btrim(em.rma)))
         em.*, o.id AS org_id
  FROM stg_employees em
  LEFT JOIN stg_companies_map cm ON cm.id = em.company_id
  LEFT JOIN organization o ON o.rma = btrim(cm.rma) AND btrim(COALESCE(cm.rma,'')) <> ''
  WHERE btrim(COALESCE(em.rma,'')) <> ''
    AND char_length(btrim(em.rma)) <= 10
    AND btrim(COALESCE(em.name,'')) <> ''
    AND o.id IS NOT NULL                            -- сирот пропускаем
  ORDER BY upper(btrim(em.rma)), em.id::bigint DESC
) e
ON CONFLICT (rma) DO NOTHING;


-- ############################################################################
-- 4) routes -> route  (~854)
-- ############################################################################
-- route.organization_rma (строка, varchar(32)) NOT NULL -> резолв через companies.rma;
--   сирот (нет company_id / организация не мигрирована) пропускаем.
-- Дедуп по (organization_rma, number) — target uq_route_org_number.
-- Коэф-ссылки legacy (winter/mountain/in_city_coef_id) НЕ переносим (id не совпадают
-- с нашими справочниками; mountain/in_city у нас — ЗНАЧЕНИЯ, а не id) -> NULL.
-- route_type_code <- legacy type_id, только если это валидный route_type.code.
-- name <- "name_a - name_b" (или что есть); number NOT NULL varchar(10).
INSERT INTO route(
  id, organization_rma, number, name, transport_type, region_id, route_type_code,
  station_coef, road_quality, excluding_coef, additional_fuel_100, additional_fuel,
  cond_fuel, heating_fuel, distance_a, distance_b, begin_path_a, begin_path_b,
  planned_lap, coe_use_capacity, average_length_pass_seat)
SELECT
  gen_random_uuid(),
  r.org_rma,
  left(btrim(r.number), 10),
  left(COALESCE(
    NULLIF(btrim(concat_ws(' - ', NULLIF(btrim(r.name_a),''), NULLIF(btrim(r.name_b),''))), ''),
    'Хатсайр ' || btrim(r.number)), 300),
  CASE WHEN r.transport_type_id ~ '^[0-9]+$' THEN r.transport_type_id::smallint END,
  CASE WHEN r.region_id ~ '^[0-9]+$' THEN r.region_id::smallint END,
  CASE WHEN r.type_id ~ '^[0-9]+$'
            AND EXISTS (SELECT 1 FROM route_type rt WHERE rt.code = r.type_id::smallint)
       THEN r.type_id::smallint END,
  CASE WHEN r.station_coef ~ '^[0-9]+$' THEN r.station_coef::smallint END,
  CASE WHEN r.road_quality ~ '^[0-9]+$' THEN r.road_quality::smallint END,
  COALESCE(CASE WHEN r.excluding_coef ~ '^[0-9]+$' THEN r.excluding_coef::int <> 0 END, false),
  CASE WHEN r.additional_fuel_100 ~ '^-?[0-9]+(\.[0-9]+)?$' THEN r.additional_fuel_100::double precision END,
  CASE WHEN r.additional_fuel ~ '^-?[0-9]+(\.[0-9]+)?$' THEN r.additional_fuel::double precision END,
  CASE WHEN r.cond_fuel ~ '^-?[0-9]+(\.[0-9]+)?$' THEN r.cond_fuel::double precision END,
  CASE WHEN r.heating_fuel ~ '^-?[0-9]+(\.[0-9]+)?$' THEN r.heating_fuel::double precision END,
  CASE WHEN r.distance_a ~ '^-?[0-9]+(\.[0-9]+)?$' THEN r.distance_a::double precision END,
  CASE WHEN r.distance_b ~ '^-?[0-9]+(\.[0-9]+)?$' THEN r.distance_b::double precision END,
  CASE WHEN r.begin_path_a ~ '^-?[0-9]+(\.[0-9]+)?$' THEN r.begin_path_a::double precision END,
  CASE WHEN r.begin_path_b ~ '^-?[0-9]+(\.[0-9]+)?$' THEN r.begin_path_b::double precision END,
  CASE WHEN r.planned_lap ~ '^[0-9]+$' THEN r.planned_lap::smallint END,
  CASE WHEN r.coe_use_capacity ~ '^-?[0-9]+(\.[0-9]+)?$' THEN r.coe_use_capacity::double precision END,
  CASE WHEN r.average_length_pass_seat ~ '^-?[0-9]+(\.[0-9]+)?$' THEN r.average_length_pass_seat::double precision END
FROM (
  SELECT DISTINCT ON (o.rma, btrim(rt.number))
         rt.*, o.rma AS org_rma
  FROM stg_routes rt
  LEFT JOIN stg_companies_map cm ON cm.id = rt.company_id
  LEFT JOIN organization o ON o.rma = btrim(cm.rma) AND btrim(COALESCE(cm.rma,'')) <> ''
  WHERE btrim(COALESCE(rt.number,'')) <> ''
    AND o.id IS NOT NULL                            -- сирот пропускаем
  ORDER BY o.rma, btrim(rt.number), rt.id::bigint DESC
) r
ON CONFLICT (organization_rma, number) DO NOTHING;
