-- ============================================================================
-- ФАЗА 5b — показатели работы архивных путевых листов. Файл 2/2: staging -> work_day, fuel_record.
-- Целевая БД: waybill.  ТОЛЬКО INSERT: никаких UPDATE/DELETE/TRUNCATE над waybill и чужими строками.
--
-- Привязка к листу — по номеру Ф5: waybill.number = 'MG'||src_code||legacy_id
-- (только source='MIGRATED', status='ARCHIVED').
--
-- Идемпотентность (повторный запуск ничего не дублирует):
--   * work_day   — лист, у которого уже есть хоть один рабочий день, пропускается целиком
--                  (NOT EXISTS) + ON CONFLICT (waybill_id, work_date) DO NOTHING;
--   * fuel_record — лист, у которого уже есть хоть одна строка топлива, пропускается целиком.
--   Архивный лист (ARCHIVED) терминален: живой поток ему дни/топливо не добавляет, поэтому
--   «уже есть строки» = «уже загружено этой фазой».
--
-- Откуда отчёты берут показатели (waybill-service):
--   * рейсы      — Σ work_day.laps (WaybillCalcAssembler, пассажирские отчёты/тренд/сводный);
--   * выручка    — Σ work_day.revenue: сводка ReportService.summary (агрегат в БД) и
--                  PassengerMetrics.earning/kassa (касса = выручка, как в legacy MBusCalc);
--   * заработок  — DriverSalary от той же выручки (× доля дохода организации);
--   * топливо    — fuel_record.fuel_given/remain_entry (ReportService.fuel/summary, норма в расчёте ПЛ).
--
-- Касты — через безопасные SQL-функции pg_temp.* (без исключений): мусор legacy
-- ('', '0000-00-00', '25:99', 'begin_path_a' и т.п.) превращается в NULL, а не роняет загрузку.
-- ============================================================================

SET client_encoding TO 'UTF8';

-- ---------------------------------------------------------------- безопасные касты
-- Вложенные CASE, а не AND: порядок вычисления операндов AND в PostgreSQL не гарантирован,
-- а каст мусора (например '0000-00-00' или '') должен выполняться только после проверки шаблона.
CREATE OR REPLACE FUNCTION pg_temp.try_date(t text) RETURNS date LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE
    WHEN btrim(t) ~ '^(19[5-9][0-9]|20[0-9]{2})-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])' THEN
      CASE WHEN substr(btrim(t), 9, 2)::int
                <= extract(day FROM (make_date(substr(btrim(t), 1, 4)::int, substr(btrim(t), 6, 2)::int, 1)
                                     + interval '1 month - 1 day'))
           THEN substr(btrim(t), 1, 10)::date END
  END
$$;

CREATE OR REPLACE FUNCTION pg_temp.try_time(t text) RETURNS time LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE WHEN btrim(t) ~ '^([01]?[0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9])?$' THEN btrim(t)::time END
$$;

-- Число с ограничением по модулю (под точность колонки назначения).
CREATE OR REPLACE FUNCTION pg_temp.try_num(t text, lim numeric) RETURNS numeric LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE WHEN btrim(t) ~ '^-?[0-9]{1,12}(\.[0-9]+)?$' THEN
           CASE WHEN abs(btrim(t)::numeric) < lim THEN round(btrim(t)::numeric, 2) END
         END
$$;

CREATE OR REPLACE FUNCTION pg_temp.try_int(t text) RETURNS integer LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE WHEN btrim(t) ~ '^[0-9]{1,9}$' THEN btrim(t)::integer END
$$;

-- ---------------------------------------------------------------- привязка staging -> лист
DROP TABLE IF EXISTS pg_temp.t5b_wb;
CREATE TEMP TABLE t5b_wb AS
SELECT s.src_code, s.legacy_id, w.id AS waybill_id, w.created_at
FROM (SELECT DISTINCT src_code, btrim(legacy_id) AS legacy_id FROM stg_wd5b
      UNION
      SELECT DISTINCT src_code, btrim(legacy_id) FROM stg_fr5b) s
JOIN waybill w ON w.number = 'MG' || s.src_code || s.legacy_id
             AND w.source = 'MIGRATED' AND w.status = 'ARCHIVED';
CREATE INDEX ON t5b_wb (src_code, legacy_id);
ANALYZE t5b_wb;

-- Нормализованные дни (дата дня; при мусорной дате — дата создания листа).
DROP TABLE IF EXISTS pg_temp.t5b_day;
CREATE TEMP TABLE t5b_day AS
SELECT m.waybill_id,
       m.created_at                                            AS wb_created,
       s.src_code,
       btrim(s.legacy_id)                                      AS legacy_id,
       pg_temp.try_int(s.seq)                                  AS seq,
       COALESCE(pg_temp.try_date(s.work_date),
                (m.created_at AT TIME ZONE 'UTC')::date)       AS work_date,
       pg_temp.try_time(s.exit_time)                           AS exit_time,
       pg_temp.try_time(s.entry_time)                          AS entry_time,
       pg_temp.try_int(s.odo_exit)                             AS odo_exit,
       pg_temp.try_int(s.odo_entry)                            AS odo_entry,
       pg_temp.try_int(s.laps)                                 AS laps,
       pg_temp.try_num(s.revenue, 1e8)                         AS revenue,
       round((extract(epoch FROM pg_temp.try_time(s.conditioner_time)) / 3600.0)::numeric, 2)
                                                               AS cond_hours,
       pg_temp.try_time(s.client_time)                         AS client_time
FROM stg_wd5b s
JOIN t5b_wb m ON m.src_code = s.src_code AND m.legacy_id = btrim(s.legacy_id);
CREATE INDEX ON t5b_day (src_code, legacy_id, seq);
ANALYZE t5b_day;

-- ---------------------------------------------------------------- work_day
-- Несколько элементов work_days с одной датой (бывает в legacy) сводятся в один день:
-- рейсы/выручка/кондиционер — сумма, одометр — min/max, время — первое выезда / последнее возврата.
INSERT INTO work_day(id, waybill_id, work_date, exit_time, entry_time, odometer_exit, odometer_entry,
                     laps, revenue, created_at, conditioner_hours, client_time)
SELECT gen_random_uuid(),
       d.waybill_id,
       d.work_date,
       (array_agg(d.exit_time   ORDER BY d.seq NULLS FIRST) FILTER (WHERE d.exit_time   IS NOT NULL))[1],
       (array_agg(d.entry_time  ORDER BY d.seq DESC NULLS LAST) FILTER (WHERE d.entry_time IS NOT NULL))[1],
       min(d.odo_exit),
       max(d.odo_entry),
       sum(d.laps),
       sum(d.revenue),
       min(d.wb_created),
       CASE WHEN sum(d.cond_hours) < 10000 THEN sum(d.cond_hours) END,
       (array_agg(d.client_time ORDER BY d.seq NULLS FIRST) FILTER (WHERE d.client_time IS NOT NULL))[1]
FROM t5b_day d
WHERE NOT EXISTS (SELECT 1 FROM work_day x WHERE x.waybill_id = d.waybill_id)
GROUP BY d.waybill_id, d.work_date
ON CONFLICT (waybill_id, work_date) DO NOTHING;

-- ---------------------------------------------------------------- fuel_record
-- Строка топлива дня (1-А/3-С) привязывается к своему рабочему дню (посуточный расчёт B10),
-- строки листа (1-АД, 5Б-БМ) и дни без загруженного work_day (2-Б) — к листу целиком.
-- created_at = дата листа + миллисекунды по порядку строк: история не «всплывает» поверх
-- живых ПЛ в подстановке остатка (FuelPrefillService сортирует по created_at), порядок строк стабилен.
INSERT INTO fuel_record(id, waybill_id, work_day_id, fuel_type, fuel_given, remain_before_exit, remain_entry,
                        created_at, additional_given, returned, coef_below_0, be_given)
SELECT gen_random_uuid(),
       m.waybill_id,
       wd.id,
       btrim(s.fuel_id)::smallint,
       pg_temp.try_num(s.fuel_given, 1e6),
       pg_temp.try_num(s.remain_before_exit, 1e6),
       pg_temp.try_num(s.remain_entry, 1e6),
       m.created_at + ((COALESCE(pg_temp.try_int(s.day_seq), 0) * 100
                        + COALESCE(pg_temp.try_int(s.line_seq), 0)) * interval '1 millisecond'),
       pg_temp.try_num(s.additional, 1e6),
       pg_temp.try_num(s.returned, 1e6),
       pg_temp.try_num(s.coef_below_0, 1e6),
       pg_temp.try_num(s.be_given, 1e6)
FROM stg_fr5b s
JOIN t5b_wb m ON m.src_code = s.src_code AND m.legacy_id = btrim(s.legacy_id)
LEFT JOIN LATERAL (
       SELECT d.work_date FROM t5b_day d
       WHERE d.src_code = s.src_code AND d.legacy_id = btrim(s.legacy_id)
         AND d.seq = pg_temp.try_int(s.day_seq)
       LIMIT 1) dd ON true
LEFT JOIN work_day wd ON wd.waybill_id = m.waybill_id AND wd.work_date = dd.work_date
WHERE btrim(COALESCE(s.fuel_id, '')) ~ '^[1-5]$'
  AND NOT EXISTS (SELECT 1 FROM fuel_record x WHERE x.waybill_id = m.waybill_id);

-- ---------------------------------------------------------------- итог
SELECT 'staging_wd' AS what, count(*) FROM stg_wd5b
UNION ALL SELECT 'staging_fr', count(*) FROM stg_fr5b
UNION ALL SELECT 'matched_waybills', count(*) FROM t5b_wb
UNION ALL SELECT 'work_day_migrated', count(*) FROM work_day d JOIN waybill w ON w.id = d.waybill_id AND w.source = 'MIGRATED'
UNION ALL SELECT 'fuel_record_migrated', count(*) FROM fuel_record f JOIN waybill w ON w.id = f.waybill_id AND w.source = 'MIGRATED';
