-- ============================================================================
-- ФАЗА 5 — исторические путевые листы. Файл 2/2: staging -> waybill.
-- Целевая БД: waybill.  Идемпотентно: INSERT ... ON CONFLICT (number) DO NOTHING.
-- НИКАКИХ DELETE/UPDATE/TRUNCATE над waybill.
--
-- Стратегия «архивная read-only запись» (вариант B плана миграции §5):
--   * status = ARCHIVED (терминальный, WaybillStatus.ARCHIVED уже есть в enum);
--   * source = 'MIGRATED' (в модели source — обычный String, не enum, безопасно);
--   * снапшоты org/vehicle/driver — самодостаточный JSON из legacy-полей
--     (name/rma/registrationNumber/brand/fullName — то, что читает отображение;
--     workflow-переходы для ARCHIVED не выполняются, id/odometer им не нужны);
--   * number = 'MG'||src_code||legacy_id — уникальный, явно «мигрированный»
--     (нац. формат RR-YY-... присваивается только в READY — историч. ПЛ его не имеют);
--     оригинальный legacy-номер сохраняется в type_data.legacyNumber;
--   * med_passed/tech_passed = true (историч. закрытые ПЛ уже прошли контроль);
--   * work_day/fuel_record НЕ переносятся (legacy хранит как сериализованный TEXT;
--     для упрощённого архива достаточно шапки).
--
-- ЗАЩИТНЫЕ КАСТЫ (в staging всё text): даты — строгий шаблон YYYY-MM-DD (в legacy
-- встречается '0000-00-00'); числа — регэксп перед ::cast.
-- ============================================================================

SET client_encoding TO 'UTF8';

INSERT INTO waybill(
  id, number, waybill_type, communication_type, status, med_passed, tech_passed,
  valid_from, valid_to, organization_rma, vehicle_reg_number, driver_rma, second_driver_rma,
  organization_snapshot, vehicle_snapshot, driver_snapshot, type_data,
  route, schedule, odometer_exit, odometer_entry, special_mark,
  print_count, source, created_at, updated_at)
SELECT
  gen_random_uuid(),
  'MG' || s.src_code || s.legacy_id,
  s.waybill_type,
  COALESCE(NULLIF(btrim(s.communication_type), ''), 'URBAN'),
  'ARCHIVED',
  true, true,
  CASE WHEN s.exit_date  ~ '^[1-9][0-9]{3}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])'
       THEN substr(s.exit_date, 1, 19)::timestamptz END,
  CASE WHEN s.entry_date ~ '^[1-9][0-9]{3}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])'
       THEN substr(s.entry_date, 1, 19)::timestamptz END,
  left(btrim(s.org_rma), 10),
  left(btrim(s.veh_reg), 20),
  left(btrim(s.driver_rma), 10),
  left(NULLIF(btrim(s.second_driver_rma), ''), 10),
  jsonb_build_object(
    'rma', btrim(s.org_rma),
    'name', NULLIF(btrim(s.org_name), ''),
    'migrated', true),
  jsonb_build_object(
    'registrationNumber', btrim(s.veh_reg),
    'brand', NULLIF(btrim(s.veh_brand), ''),
    'migrated', true),
  jsonb_build_object(
    'rma', btrim(s.driver_rma),
    'fullName', NULLIF(btrim(s.driver_name), ''),
    'migrated', true),
  jsonb_strip_nulls(jsonb_build_object(
    'migrated', true,
    'legacyTable', s.src_code,
    'legacyId', s.legacy_id,
    'legacyNumber', NULLIF(btrim(s.number), ''),
    'typeService', NULLIF(btrim(s.type_service), ''),
    'secondDriverName', NULLIF(btrim(s.second_driver_name), ''))),
  left(NULLIF(btrim(s.route_text), ''), 300),
  left(NULLIF(btrim(s.schedule), ''), 100),
  CASE WHEN s.odo_exit  ~ '^[0-9]{1,9}$' THEN s.odo_exit::int  END,
  CASE WHEN s.odo_entry ~ '^[0-9]{1,9}$' THEN s.odo_entry::int END,
  left(NULLIF(btrim(s.special_mark), ''), 1000),
  0,
  'MIGRATED',
  COALESCE(
    CASE WHEN s.created_at ~ '^[1-9][0-9]{3}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])'
         THEN substr(s.created_at, 1, 19)::timestamptz END,
    now()),
  now()
FROM stg_wb5 s
WHERE btrim(COALESCE(s.legacy_id, '')) ~ '^[0-9]+$'
  AND btrim(COALESCE(s.org_rma, ''))    <> ''
  AND btrim(COALESCE(s.veh_reg, ''))    <> ''
  AND btrim(COALESCE(s.driver_rma, '')) <> ''
ON CONFLICT (number) DO NOTHING;
