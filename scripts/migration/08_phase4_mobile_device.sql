-- ============================================================================
-- ФАЗА 4 — реестр мобильных устройств. Файл 2/2: staging -> mobile_device.
-- Предполагается, что staging уже загружен (orchestrator сделал COPY).
-- Идемпотентно: INSERT ... WHERE NOT EXISTS по натуральному ключу.
-- НИКАКИХ DELETE / UPDATE / TRUNCATE наших данных (3 демо-записи не трогаем).
--
-- МАППИНГ phone_infos -> mobile_device:
--   company_id -> companies.rma -> organization.rma  => organization_rma (NOT NULL).
--                Сироты (компания не мигрирована / rma пустой) — ПРОПУСКАЕМ (JOIN).
--   user_id (=drivers.id) -> drivers.rma             => driver_rma (nullable).
--   user_id (=drivers.id) -> drivers.full_name       => driver_name (NOT NULL),
--                если ФИО пустое -> '—'.
--   brand   -> brand (nullable, если пусто -> NULL).
--   model   -> model (NOT NULL), если пусто -> '—'.
--   created_at -> authorized_at (NOT NULL; created_at в legacy всегда заполнен).
--
-- ИДЕМПОТЕНТНОСТЬ (у mobile_device нет натурального UNIQUE-ключа — у водителя
-- может быть несколько устройств):
--   1) внутри одного прогона схлопываем дубли DISTINCT ON натурального ключа
--      (organization_rma, driver_rma, model, authorized_at), берём макс. legacy id;
--   2) при повторном прогоне вставляем только те, которых ещё нет в target —
--      WHERE NOT EXISTS по тому же ключу (driver_rma сравниваем через
--      IS NOT DISTINCT FROM, чтобы NULL матчился с NULL и не плодил дубли).
--
-- TimeZone фиксируем в UTC, чтобы created_at::timestamptz давал ДЕТЕРМИНИРОВАННЫЙ
-- инстант между прогонами (иначе NOT EXISTS не поймает уже вставленную строку).
-- ============================================================================

SET client_encoding TO 'UTF8';
SET TimeZone TO 'UTC';

WITH mapped AS (
  SELECT
    o.rma                                                    AS org_rma,
    left(NULLIF(btrim(drv.rma), ''), 32)                     AS driver_rma,
    left(COALESCE(NULLIF(btrim(drv.full_name), ''), '—'), 200) AS driver_name,
    left(NULLIF(btrim(pi.brand), ''), 120)                   AS brand,
    left(COALESCE(NULLIF(btrim(pi.model), ''), '—'), 120)    AS model,
    pi.created_at::timestamptz                               AS authorized_at,
    pi.id::bigint                                            AS lid
  FROM stg_phone_infos pi
  JOIN stg_companies_map cm ON cm.id = pi.company_id
  JOIN organization o
       ON o.rma = btrim(cm.rma) AND btrim(COALESCE(cm.rma, '')) <> ''  -- сирот пропускаем
  LEFT JOIN stg_phone_drivers drv ON drv.id = pi.user_id
  WHERE pi.created_at ~ '^[1-9][0-9]{3}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])'
),
dedup AS (
  SELECT DISTINCT ON (org_rma, driver_rma, model, authorized_at) *
  FROM mapped
  ORDER BY org_rma, driver_rma, model, authorized_at, lid DESC
)
INSERT INTO mobile_device(
  id, organization_rma, driver_rma, driver_name, brand, model,
  authorized_at, created_at, updated_at)
SELECT
  gen_random_uuid(), d.org_rma, d.driver_rma, d.driver_name, d.brand, d.model,
  d.authorized_at, now(), now()
FROM dedup d
WHERE NOT EXISTS (
  SELECT 1 FROM mobile_device md
  WHERE md.organization_rma = d.org_rma
    AND md.driver_rma IS NOT DISTINCT FROM d.driver_rma
    AND md.model          = d.model
    AND md.authorized_at  = d.authorized_at
);
