-- ============================================================================
-- ФАЗА 1b — нормативы расхода топлива марок (legacy brands -> brand).
-- Файл 2/3. Предполагается, что stg_brand_norms загружен (run_phase1b.ps1 сделал COPY).
--
-- ПОЧЕМУ НУЖНА. Справочник марок master-data засеян миграцией V58 («копия справочника
-- марок из боевого MinTransRT», 491 строка) БЕЗ топливных JSON и без кода марки. Фаза 1
-- (02_phase1_reference.sql) вставляет марки с ключом (lower(name), lower(model)) и
-- ON CONFLICT DO NOTHING — все legacy-марки уже были, поэтому нормативы не перенеслись:
-- fuel_100 заполнен у 11 марок из 497 (6 из V27 + 5 из V30), в legacy — у 309 из 491.
-- Итог — норма топлива 0 у всех архивных листов (отчёты, расчёт), а у грузовых ещё и
-- тип кузова «нет» (код марки number пуст -> кузов '9', расход не нормируется).
--
-- ЧТО ДЕЛАЕТ. UPDATE ТОЛЬКО пустых полей существующих марок (справочник, <= 500 строк):
--   fuel_100, fuel_100_dushanbe, fuel_hour  — если у нас NULL / '' / '[]' / 'null';
--   fuel_interior_heating                   — если у нас NULL или 0;
--   number (код марки: 1-я цифра — кузов, 5-я — прицеп) — если у нас NULL / ''.
-- Заполненное у нас (V27/V30, правки администратора) НЕ перезаписывается. Новые марки
-- не вставляются (это делает фаза 1).
--
-- ДУБЛИ ВИДА ТОПЛИВА. У 118 марок legacy в fuel_100 одна и та же строка fuel_id дважды
-- (у 6 — с разными нормами). legacy читает таблицу через array_column($fuels,
-- 'consumption', 'fuel_id') — на месте первого вхождения остаётся норма ПОСЛЕДНЕГО.
-- Пассажирский расчёт e-Waybill до 24.09 проходил по каждой строке и удвоил бы норматив,
-- поэтому массив переносится уже схлопнутым по той же семантике (массивы без дублей —
-- текстом как есть). Расчёт схлопывает и сам (FuelNormCalculator.resolveNorms) — на
-- случай дублей, введённых вручную.
--
-- Сопоставление: lower(name) + lower(model) — тот же ключ, что uq_brand_name_model.
-- Дубли ключа в legacy (5 пар): берём строку с непустым fuel_100, затем с большим id.
-- Повторный прогон ничего не меняет (условие «у нас пусто» уже ложно).
-- ============================================================================

SET client_encoding TO 'UTF8';

-- array_column($a, 'consumption', 'fuel_id'): по дублю fuel_id — позиция первого, строка последнего.
-- Без дублей / не JSON-массив — текст без изменений.
CREATE FUNCTION pg_temp.norms_last_wins(src text) RETURNS text LANGUAGE plpgsql AS $$
DECLARE
  j jsonb;
  total int;
  distinct_ids int;
  res text;
BEGIN
  IF src IS NULL THEN RETURN NULL; END IF;
  BEGIN
    j := src::jsonb;
  EXCEPTION WHEN others THEN
    RETURN src;
  END;
  IF jsonb_typeof(j) <> 'array' THEN RETURN src; END IF;
  SELECT count(*), count(DISTINCT e.elem->>'fuel_id') INTO total, distinct_ids
  FROM jsonb_array_elements(j) AS e(elem);
  IF total = distinct_ids THEN RETURN src; END IF;
  SELECT jsonb_agg(last.elem ORDER BY f.first_pos)::text INTO res
  FROM (SELECT a.elem->>'fuel_id' AS fid, min(a.pos) AS first_pos, max(a.pos) AS last_pos
          FROM jsonb_array_elements(j) WITH ORDINALITY AS a(elem, pos)
         GROUP BY 1) f
  JOIN jsonb_array_elements(j) WITH ORDINALITY AS last(elem, pos) ON last.pos = f.last_pos;
  RETURN res;
END $$;

CREATE TEMP TABLE tmp_brand_src AS
SELECT DISTINCT ON (lower(s.name), lower(COALESCE(s.model, '')))
       s.id, s.name, COALESCE(s.model, '') AS model,
       NULLIF(btrim(s.number), '')                                            AS number,
       CASE WHEN COALESCE(btrim(s.fuel_100), '') IN ('', '[]', 'null') THEN NULL
            ELSE pg_temp.norms_last_wins(btrim(s.fuel_100)) END               AS fuel_100,
       CASE WHEN COALESCE(btrim(s.fuel_100_dushanbe), '') IN ('', '[]', 'null') THEN NULL
            ELSE pg_temp.norms_last_wins(btrim(s.fuel_100_dushanbe)) END      AS fuel_100_dushanbe,
       CASE WHEN COALESCE(btrim(s.fuel_hour), '') IN ('', '[]', 'null') THEN NULL
            ELSE pg_temp.norms_last_wins(btrim(s.fuel_hour)) END              AS fuel_hour,
       CASE WHEN s.fuel_interior_heating ~ '^[0-9]+(\.[0-9]+)?$'
             AND s.fuel_interior_heating::double precision > 0
            THEN s.fuel_interior_heating::double precision END                AS fuel_interior_heating
FROM stg_brand_norms s
WHERE btrim(COALESCE(s.name, '')) <> ''
ORDER BY lower(s.name), lower(COALESCE(s.model, '')),
         (COALESCE(btrim(s.fuel_100), '') NOT IN ('', '[]', 'null')) DESC,
         s.id::bigint DESC;

-- Что будет изменено (для журнала прогона).
SELECT count(*) FILTER (WHERE COALESCE(btrim(b.fuel_100), '') IN ('', '[]', 'null') AND t.fuel_100 IS NOT NULL)
         AS fill_fuel_100,
       count(*) FILTER (WHERE COALESCE(btrim(b.fuel_100_dushanbe), '') IN ('', '[]', 'null') AND t.fuel_100_dushanbe IS NOT NULL)
         AS fill_fuel_100_dushanbe,
       count(*) FILTER (WHERE COALESCE(btrim(b.fuel_hour), '') IN ('', '[]', 'null') AND t.fuel_hour IS NOT NULL)
         AS fill_fuel_hour,
       count(*) FILTER (WHERE COALESCE(b.fuel_interior_heating, 0) = 0 AND t.fuel_interior_heating IS NOT NULL)
         AS fill_interior_heating,
       count(*) FILTER (WHERE COALESCE(btrim(b.number), '') = '' AND t.number IS NOT NULL)
         AS fill_number,
       count(*) AS matched_brands
FROM brand b
JOIN tmp_brand_src t ON lower(b.name) = lower(t.name) AND lower(b.model) = lower(t.model);

UPDATE brand b SET
  fuel_100 = CASE WHEN COALESCE(btrim(b.fuel_100), '') IN ('', '[]', 'null') AND t.fuel_100 IS NOT NULL
                  THEN t.fuel_100 ELSE b.fuel_100 END,
  fuel_100_dushanbe = CASE WHEN COALESCE(btrim(b.fuel_100_dushanbe), '') IN ('', '[]', 'null') AND t.fuel_100_dushanbe IS NOT NULL
                  THEN t.fuel_100_dushanbe ELSE b.fuel_100_dushanbe END,
  fuel_hour = CASE WHEN COALESCE(btrim(b.fuel_hour), '') IN ('', '[]', 'null') AND t.fuel_hour IS NOT NULL
                  THEN t.fuel_hour ELSE b.fuel_hour END,
  fuel_interior_heating = CASE WHEN COALESCE(b.fuel_interior_heating, 0) = 0 AND t.fuel_interior_heating IS NOT NULL
                  THEN t.fuel_interior_heating ELSE b.fuel_interior_heating END,
  number = CASE WHEN COALESCE(btrim(b.number), '') = '' AND t.number IS NOT NULL
                  THEN left(t.number, 50) ELSE b.number END,
  updated_at = now()
FROM tmp_brand_src t
WHERE lower(b.name) = lower(t.name) AND lower(b.model) = lower(t.model)
  AND (   (COALESCE(btrim(b.fuel_100), '') IN ('', '[]', 'null') AND t.fuel_100 IS NOT NULL)
       OR (COALESCE(btrim(b.fuel_100_dushanbe), '') IN ('', '[]', 'null') AND t.fuel_100_dushanbe IS NOT NULL)
       OR (COALESCE(btrim(b.fuel_hour), '') IN ('', '[]', 'null') AND t.fuel_hour IS NOT NULL)
       OR (COALESCE(b.fuel_interior_heating, 0) = 0 AND t.fuel_interior_heating IS NOT NULL)
       OR (COALESCE(btrim(b.number), '') = '' AND t.number IS NOT NULL));

-- Итог: сколько марок теперь с нормативом.
SELECT count(*) AS brands_total,
       count(*) FILTER (WHERE COALESCE(btrim(fuel_100), '') NOT IN ('', '[]', 'null')) AS with_fuel_100,
       count(*) FILTER (WHERE COALESCE(btrim(number), '') <> '') AS with_number
FROM brand;

DROP TABLE tmp_brand_src;
