-- ============================================================================
-- СПРАВКИ (маълумотнома) — staging -> malumotnoma_route / malumotnoma / malumotnoma_line.
-- Файл 2/2. Идемпотентно: вставляется только то, чего ещё нет (маршрут — по legacy_id,
-- справка — по номеру). НИКАКИХ DELETE; тарифы уже перенесённых маршрутов не трогаем
-- (администратор мог их поправить).
--
-- Маршруты: 674 тарифицированных маршрута «Роҳхат» (routemalumotnomas). Демо-маршруты V12
-- с выдуманными тарифами отключаются (active = false), как только справочник загружен.
--
-- Справки: номер = legacy id («Рақами маълумотнома» — сквозная нумерация продолжается, V31),
-- legacy = true (правке не подлежат). Цена — как её считает и печатает «Роҳхат»
-- (MalumotnomaCalc): тариф вида транспорта × 2 при «туда и обратно» по каждой строке,
-- сумма × 0.5 для льготной; строки с удалённым/пустым маршрутом пропускаются (как в legacy).
-- Кассир — имя пользователя «Роҳхат» (issuer_rma нет: учётки кассиров не переносились),
-- организация не указана — архив видят платформенные роли. Удалённые в «Роҳхат» (soft delete)
-- переносятся аннулированными: номер в журнале остаётся, в отчёт кассира не входят.
--
-- Номера, уже занятые справками e-Waybill (выданными до переноса), — ОШИБКА: перенос нужно
-- делать до начала выдачи справок в e-Waybill (см. infra/DEPLOY.md).
-- ============================================================================
SET client_encoding TO 'UTF8';
SET TimeZone TO 'UTC';

DO $$
DECLARE n bigint;
BEGIN
  SELECT count(*) INTO n FROM stg_mlm s JOIN malumotnoma m ON m.number = s.id AND NOT m.legacy;
  IF n > 0 THEN
    RAISE EXCEPTION 'Номера % справок «Роҳхат» уже заняты справками e-Waybill — перенос остановлен', n;
  END IF;
END $$;

-- 1) Маршруты справок.
INSERT INTO malumotnoma_route (id, name, distance_km, car_price, mbus_price, bus_price, active, legacy_id,
                               created_at, updated_at)
SELECT gen_random_uuid(),
       left(COALESCE(NULLIF(regexp_replace(btrim(s.name), '\s+', ' ', 'g'), ''), '—'), 300),
       COALESCE(s.distance, 0),
       round(COALESCE(s.car_price, 0), 2),
       round(COALESCE(s.mbus_price, 0), 2),
       round(COALESCE(s.bus_price, 0), 2),
       true, s.id, now(), now()
FROM stg_mlm_route s
WHERE NOT EXISTS (SELECT 1 FROM malumotnoma_route r WHERE r.legacy_id = s.id);

UPDATE malumotnoma_route
SET active = false, updated_at = now()
WHERE legacy_id IS NULL AND active
  AND name IN ('Душанбе — Хуҷанд', 'Душанбе — Бохтар', 'Душанбе — Кӯлоб', 'Хуҷанд — Исфара')
  AND EXISTS (SELECT 1 FROM malumotnoma_route WHERE legacy_id IS NOT NULL);

-- 2) Строки справок с ценой по тарифу «Роҳхат» и порядком (как в legacy — по id строки).
CREATE TEMP TABLE t_lines AS
SELECT l.malumotnoma_id AS number,
       r.id             AS route_id,
       (l.round_trip = 1) AS round_trip,
       (row_number() OVER (PARTITION BY l.malumotnoma_id ORDER BY l.id) - 1)::smallint AS position,
       round((CASE COALESCE(m.transport_type_id, 4)
                WHEN 1 THEN COALESCE(sr.bus_price, 0)
                WHEN 3 THEN COALESCE(sr.mbus_price, 0)
                WHEN 4 THEN COALESCE(sr.car_price, 0)
                ELSE 0 END) * (CASE WHEN l.round_trip = 1 THEN 2 ELSE 1 END), 2) AS price,
       r.name || CASE WHEN l.round_trip = 1 THEN ' (сафари рафту баргашт)' ELSE '' END AS label
FROM stg_mlm_line l
JOIN stg_mlm m ON m.id = l.malumotnoma_id
JOIN stg_mlm_route sr ON sr.id = l.route_id
JOIN malumotnoma_route r ON r.legacy_id = l.route_id;

-- 3) Справки.
INSERT INTO malumotnoma (id, number, fio, transport_type_id, age, organization_rma, issuer_rma, issuer_name,
                         updater_name, price, route_summary, legacy, annulled_at, annulled_by, annul_reason,
                         created_at, updated_at)
SELECT gen_random_uuid(),
       s.id,
       left(COALESCE(NULLIF(regexp_replace(btrim(s.fio), '\s+', ' ', 'g'), ''), '—'), 300),
       COALESCE(s.transport_type_id, 4)::smallint,
       (CASE WHEN s.age = 1 THEN 1 ELSE 0 END)::smallint,
       NULL, NULL,
       left(NULLIF(btrim(s.create_user), ''), 200),
       left(NULLIF(btrim(s.update_user), ''), 200),
       round(COALESCE(a.total, 0) * (CASE WHEN s.age = 1 THEN 0.5 ELSE 1 END), 2),
       left(a.summary, 1000),
       true,
       CASE WHEN s.deleted_at ~ '^[0-9]{4}-' THEN substr(s.deleted_at, 1, 19)::timestamptz END,
       CASE WHEN s.deleted_at ~ '^[0-9]{4}-' THEN 'Роҳхат' END,
       CASE WHEN s.deleted_at ~ '^[0-9]{4}-' THEN 'Удалена в старой системе «Роҳхат»' END,
       COALESCE(CASE WHEN s.created_at ~ '^[0-9]{4}-' THEN substr(s.created_at, 1, 19)::timestamptz END,
                CASE WHEN s.updated_at ~ '^[0-9]{4}-' THEN substr(s.updated_at, 1, 19)::timestamptz END,
                timestamptz '2023-01-01 00:00:00+00'),
       COALESCE(CASE WHEN s.updated_at ~ '^[0-9]{4}-' THEN substr(s.updated_at, 1, 19)::timestamptz END,
                CASE WHEN s.created_at ~ '^[0-9]{4}-' THEN substr(s.created_at, 1, 19)::timestamptz END,
                timestamptz '2023-01-01 00:00:00+00')
FROM stg_mlm s
LEFT JOIN (SELECT number, sum(price) AS total, string_agg(label, ', ' ORDER BY position) AS summary
           FROM t_lines GROUP BY number) a ON a.number = s.id
WHERE NOT EXISTS (SELECT 1 FROM malumotnoma m WHERE m.number = s.id);

INSERT INTO malumotnoma_line (id, malumotnoma_id, malumotnoma_route_id, round_trip, price, position)
SELECT gen_random_uuid(), m.id, t.route_id, t.round_trip, t.price, t.position
FROM t_lines t
JOIN malumotnoma m ON m.number = t.number AND m.legacy
WHERE NOT EXISTS (SELECT 1 FROM malumotnoma_line x WHERE x.malumotnoma_id = m.id);

-- 4) Сквозная нумерация — следующая справка e-Waybill получает номер после последнего перенесённого.
SELECT setval('malumotnoma_number_seq',
              GREATEST((SELECT max(number) FROM malumotnoma),
                       (SELECT CASE WHEN is_called THEN last_value ELSE last_value - 1 END
                        FROM malumotnoma_number_seq)));
