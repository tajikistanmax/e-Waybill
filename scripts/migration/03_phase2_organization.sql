-- ============================================================================
-- Файл 3/3: ФАЗА 2 — организации. companies (deleted_at IS NULL) -> organization.
-- Маппинг по §4 плана миграции (spec/notes/data-migration-plan.md).
-- Идемпотентно: ON CONFLICT (rma) DO NOTHING — наши тестовые орг с теми же РМА
-- НЕ трогаем. НИКАКИХ DELETE/UPDATE.
--
-- Отбрасываем:
--   * записи без РМА (rma NULL/'' — колонка organization.rma NOT NULL);
--   * дубли по РМА в источнике — DISTINCT ON (rma), берём запись с макс. legacy id.
-- Защитные касты:
--   * даты лицензии — только если строка похожа на YYYY-MM-DD, иначе NULL;
--   * latitude/longitude — только валидное десятичное число < 1000, иначе NULL
--     (в legacy есть мусор вида '38.533.2767' и координаты в градусах/минутах);
--   * ownership — только 1/2 (CHECK organization_ownership_range), иначе NULL;
--   * type_company — из type_company_id, дефолт 1 (NOT NULL).
-- Дефолты нашей таблицы: subject_type='LEGAL', source='MIGRATED', blocked из
-- status_lock, created_at/updated_at = now(), id = gen_random_uuid().
-- ============================================================================

INSERT INTO organization(
  id, rma, kpp, name, type_company, region_id, address, phone, email, name_head,
  bank, license_from, license_to, blocked, subject_type, source, percent_income,
  cat_1, cat_2, cat_3, carrier_license_number, ownership, latitude, longitude,
  registration_cert_number, extract_number, vat_cert_number, plan_pass_volume,
  plan_pass_traffic, created_at, updated_at)
SELECT
  gen_random_uuid(),
  btrim(rma),
  NULLIF(btrim(kpp),''),
  name,
  COALESCE(NULLIF(type_company_id,'')::smallint, 1),
  NULLIF(region_id,'')::smallint,
  NULLIF(address,''),
  NULLIF(phone,''),
  NULLIF(email,''),
  NULLIF(name_head,''),
  NULLIF(bank,''),
  CASE WHEN license_activity_from ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}'
       THEN substr(license_activity_from,1,10)::date END,
  CASE WHEN license_activity_to ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}'
       THEN substr(license_activity_to,1,10)::date END,
  COALESCE(NULLIF(status_lock,'')::int, 0) = 1,
  'LEGAL',
  'MIGRATED',
  NULLIF(percent_income,'')::double precision,
  NULLIF(cat_1,'')::smallint,
  NULLIF(cat_2,'')::smallint,
  NULLIF(cat_3,'')::smallint,
  NULLIF(license_number,''),
  CASE WHEN ownership_id IN ('1','2') THEN ownership_id::smallint END,
  CASE WHEN latitude  ~ '^-?[0-9]+(\.[0-9]+)?$' AND abs(latitude::numeric)  < 1000
       THEN latitude::numeric  END,
  CASE WHEN longitude ~ '^-?[0-9]+(\.[0-9]+)?$' AND abs(longitude::numeric) < 1000
       THEN longitude::numeric END,
  NULLIF(registration_certificate,''),
  NULLIF(iktibos,''),
  NULLIF(aai,''),
  NULLIF(plan_pass_volume,'')::numeric,
  NULLIF(plan_pass_traffic,'')::numeric,
  now(), now()
FROM (
  SELECT DISTINCT ON (btrim(rma)) *
  FROM stg_companies
  WHERE btrim(COALESCE(rma,'')) <> ''
    AND btrim(COALESCE(name,'')) <> ''
  ORDER BY btrim(rma), id::bigint DESC
) s
ON CONFLICT (rma) DO NOTHING;
