-- ============================================================================
-- ФАЗА 2b — организации без РМА. Файл 2/3.
-- stg_b_companies (176 орфан-компаний, deleted_at IS NULL AND rma пустой) ->
-- organization с СИНТЕТИЧЕСКИМ ключом rma = 'MIG' || legacy_company_id.
--   * детерминированно от id, уникально (id уникален), явно синтетическое;
--   * влезает в varchar(10): 'MIG14'..'MIG635' (id 14..635).
-- Остальной маппинг ПОЛНОСТЬЮ идентичен Ф2 (03_phase2_organization.sql):
--   license_number->carrier_license_number, ownership_id->ownership (только 1/2),
--   type_company_id->type_company, registration_certificate->registration_cert_number,
--   iktibos->extract_number, aai->vat_cert_number, координаты (гард), даты (гард).
-- Дефолты: subject_type='LEGAL', source='MIGRATED', blocked из status_lock,
--   id=gen_random_uuid(), created_at/updated_at=now().
-- Идемпотентно: ON CONFLICT (rma) DO NOTHING. НИКАКИХ DELETE/UPDATE/TRUNCATE.
-- ============================================================================

SET client_encoding TO 'UTF8';

INSERT INTO organization(
  id, rma, kpp, name, type_company, region_id, address, phone, email, name_head,
  bank, license_from, license_to, blocked, subject_type, source, percent_income,
  cat_1, cat_2, cat_3, carrier_license_number, ownership, latitude, longitude,
  registration_cert_number, extract_number, vat_cert_number, plan_pass_volume,
  plan_pass_traffic, created_at, updated_at)
SELECT
  gen_random_uuid(),
  'MIG' || btrim(id),                         -- синтетический ключ от legacy id
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
  SELECT DISTINCT ON (btrim(id)) *
  FROM stg_b_companies
  WHERE btrim(COALESCE(id,'')) <> ''
    AND btrim(COALESCE(name,'')) <> ''
  ORDER BY btrim(id), id::bigint DESC
) s
ON CONFLICT (rma) DO NOTHING;
