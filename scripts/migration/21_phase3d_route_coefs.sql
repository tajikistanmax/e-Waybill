-- ============================================================================
-- ФАЗА 3d — коэффициенты маршрутов, которые фазы 3/3b не перенесли (находка 29 AUDIT.md).
-- Предполагается, что stg_routes_3c загружен (run_phase1b.ps1 сделал COPY).
--
-- ПОЧЕМУ НУЖНА. Фазы 3/3b вставили маршруты без зимнего, горного и городского
-- коэффициентов (05_phase3_registries.sql: «коэф-ссылки legacy НЕ переносим»). В legacy
-- они есть у ~440 маршрутов, у нас были у единиц — норма топлива на этих маршрутах
-- (в том числе архивных листов) занижена: legacy getCoef() складывает
--   K = зимний(в период) + горный + станционный + городской + износ − качество дороги.
--
-- ЧТО ДЕЛАЕТ. ТОЛЬКО UPDATE пустых полей существующих маршрутов:
--   winter_coef_id      — наш fuel_winter_coef с тем же названием периода, что у legacy;
--   mountain_coef_value — legacy mountain_coef_id (в legacy это и есть процент, см. getCoef);
--   in_city_coef_value  — legacy in_city_coef_id (так же).
-- Маршрут legacy ↔ наш: организация + номер (как в Ф3: left(btrim(number), 10)) + название
-- («name_a - name_b»); при нескольких legacy-строках — с наибольшим id (тот же выбор, что Ф3).
-- Уже заполненные поля не трогает (могли поправить вручную). Повторный прогон — 0 строк.
-- ============================================================================

SET client_encoding TO 'UTF8';

CREATE TEMP TABLE tmp_coef_match AS
SELECT DISTINCT ON (t.id)
       t.id AS route_id,
       (SELECT w.id FROM fuel_winter_coef w
         WHERE lower(btrim(w.name)) = lower(btrim(s.winter_name)) ORDER BY w.id LIMIT 1) AS winter_id,
       CASE WHEN s.mountain_coef_id ~ '^[0-9]+$' AND s.mountain_coef_id::int > 0
            THEN s.mountain_coef_id::smallint END AS mountain,
       CASE WHEN s.in_city_coef_id ~ '^[0-9]+$' AND s.in_city_coef_id::int > 0
            THEN s.in_city_coef_id::smallint END AS in_city
FROM stg_routes_3c s
JOIN route t
  ON t.organization_rma = btrim(s.company_rma)
 AND t.number = left(btrim(s.number), 10)
 AND lower(btrim(t.name)) = lower(left(COALESCE(
       NULLIF(btrim(concat_ws(' - ', NULLIF(btrim(s.name_a), ''), NULLIF(btrim(s.name_b), ''))), ''),
       'Хатсайр ' || btrim(s.number)), 300))
WHERE btrim(COALESCE(s.company_rma, '')) <> ''
  AND btrim(COALESCE(s.number, '')) <> ''
ORDER BY t.id, s.id::bigint DESC;

SELECT count(*)                                   AS matched_routes,
       count(*) FILTER (WHERE winter_id IS NOT NULL) AS legacy_with_winter,
       count(*) FILTER (WHERE mountain IS NOT NULL)  AS legacy_with_mountain,
       count(*) FILTER (WHERE in_city IS NOT NULL)   AS legacy_with_in_city
FROM tmp_coef_match;

UPDATE route t
SET winter_coef_id      = COALESCE(t.winter_coef_id, m.winter_id),
    mountain_coef_value = COALESCE(t.mountain_coef_value, m.mountain),
    in_city_coef_value  = COALESCE(t.in_city_coef_value, m.in_city)
FROM tmp_coef_match m
WHERE t.id = m.route_id
  AND ((t.winter_coef_id IS NULL AND m.winter_id IS NOT NULL)
    OR (t.mountain_coef_value IS NULL AND m.mountain IS NOT NULL)
    OR (t.in_city_coef_value IS NULL AND m.in_city IS NOT NULL));

SELECT count(*) AS routes_total,
       count(winter_coef_id) AS with_winter,
       count(mountain_coef_value) AS with_mountain,
       count(in_city_coef_value) AS with_in_city
FROM route;

DROP TABLE tmp_coef_match;
