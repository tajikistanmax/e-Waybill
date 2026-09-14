-- ============================================================================
-- ФАЗА 3b — ТС/водители/сотрудники/маршруты орфан-компаний. Файл 3/3.
-- Переподтягиваем ТОЛЬКО сирот (записи орфан-компаний, чьи организации теперь
-- заведены в Ф2b с ключом 'MIG'||id). Идемпотентно поверх уже загруженного Ф3:
-- INSERT ... ON CONFLICT DO NOTHING — уже мигрированные записи не тронутся.
--
-- РЕЗОЛВ ОРГАНИЗАЦИИ (COALESCE-ключ, как требует ТЗ):
--   X.company_id -> stg_b_companies.id -> org_rma = COALESCE(NULLIF(rma,''),'MIG'||id)
--   -> organization по rma -> id. Для орфан-компаний rma пустой, значит org_rma
--   всегда = 'MIG'||id. Сирот без организации (o.id IS NULL) пропускаем.
--
-- ЗАЩИТНЫЕ КАСТЫ — идентичны Ф3 (05_phase3_registries.sql): даты строгим шаблоном
--   YYYY-MM-DD, числа регэксп-проверкой перед ::cast (в staging всё text).
-- НИКАКИХ DELETE/UPDATE/TRUNCATE.
-- ============================================================================

SET client_encoding TO 'UTF8';


-- ############################################################################
-- 1) parkings (сироты) -> vehicle
-- ############################################################################
-- Дедуп по registration_number (target vehicle_registration_number_key UNIQUE):
--   DISTINCT ON (upper(btrim(registration_number))), макс. legacy id. Кросс-набор
--   (совпадение с уже загруженными Ф3) ловит ON CONFLICT (registration_number).
-- vincode: частичный UNIQUE uq_vehicle_vincode -> оставляем vincode только у ПЕРВОЙ
--   записи в группе (vin_rn=1) и только если такого vincode ещё нет в target (в т.ч.
--   среди загруженных Ф3); иначе NULL (ON CONFLICT ловит лишь registration_number).
-- Пустой госномер — пропускаем.
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
    FROM stg_b_parkings p
    LEFT JOIN stg_b_companies cm ON cm.id = p.company_id
    LEFT JOIN organization o
           ON o.rma = COALESCE(NULLIF(btrim(cm.rma),''), 'MIG' || btrim(cm.id))
    LEFT JOIN stg_b_brands_map bm ON bm.id = p.brand_id
    WHERE btrim(COALESCE(p.registration_number,'')) <> ''
      AND o.id IS NOT NULL                          -- сирот без орг пропускаем
    ORDER BY upper(btrim(p.registration_number)), p.id::bigint DESC
  ) dd
) d
ON CONFLICT (registration_number) DO NOTHING;


-- ############################################################################
-- 2) drivers (сироты) -> driver
-- ############################################################################
-- Дедуп по rma (target driver_rma_key UNIQUE, varchar(10)): rma NOT NULL, <=10;
--   DISTINCT ON (upper(btrim(rma))), макс. id. Кросс-набор ловит ON CONFLICT (rma).
-- Водители с пустым/мусорным/длинным rma по-прежнему ПРОПУСКАЮТСЯ (это ок).
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
  FROM stg_b_drivers dr
  LEFT JOIN stg_b_companies cm ON cm.id = dr.company_id
  LEFT JOIN organization o
         ON o.rma = COALESCE(NULLIF(btrim(cm.rma),''), 'MIG' || btrim(cm.id))
  WHERE btrim(COALESCE(dr.rma,'')) <> ''
    AND char_length(btrim(dr.rma)) <= 10
    AND btrim(COALESCE(dr.full_name,'')) <> ''
    AND o.id IS NOT NULL                            -- сирот без орг пропускаем
  ORDER BY upper(btrim(dr.rma)), dr.id::bigint DESC
) d
ON CONFLICT (rma) DO NOTHING;


-- ############################################################################
-- 3) employees (сироты) -> employee
-- ############################################################################
-- target employee.rma NOT NULL UNIQUE varchar(10) -> без rma запись не вставить.
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
  FROM stg_b_employees em
  LEFT JOIN stg_b_companies cm ON cm.id = em.company_id
  LEFT JOIN organization o
         ON o.rma = COALESCE(NULLIF(btrim(cm.rma),''), 'MIG' || btrim(cm.id))
  WHERE btrim(COALESCE(em.rma,'')) <> ''
    AND char_length(btrim(em.rma)) <= 10
    AND btrim(COALESCE(em.name,'')) <> ''
    AND o.id IS NOT NULL                            -- сирот без орг пропускаем
  ORDER BY upper(btrim(em.rma)), em.id::bigint DESC
) e
ON CONFLICT (rma) DO NOTHING;


-- ############################################################################
-- 4) routes (сироты) -> route
-- ############################################################################
-- route.organization_rma (varchar(32)) NOT NULL -> резолв COALESCE-ключом.
-- Дедуп по (organization_rma, number) — target uq_route_org_number.
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
  FROM stg_b_routes rt
  LEFT JOIN stg_b_companies cm ON cm.id = rt.company_id
  LEFT JOIN organization o
         ON o.rma = COALESCE(NULLIF(btrim(cm.rma),''), 'MIG' || btrim(cm.id))
  WHERE btrim(COALESCE(rt.number,'')) <> ''
    AND o.id IS NOT NULL                            -- сирот без орг пропускаем
  ORDER BY o.rma, btrim(rt.number), rt.id::bigint DESC
) r
ON CONFLICT (organization_rma, number) DO NOTHING;
