-- Маълумотнома (сверка 25.09, E5/B7): сквозной номер вместо UUID, цена строки на момент выдачи,
-- порядок маршрутов, аннулирование вместо удаления, ключ маршрута старой системы.

-- Сквозной номер. В «Роҳхат» номер справки — её id («Рақами маълумотнома», на бланке «№ {id}»);
-- последний в дампе 07.08.2026 — 53802, поэтому новые продолжают с 53803. Архив справок старой
-- системы переносится с номером = legacy id (scripts/migration/run_malumotnoma.ps1), скрипт
-- сдвигает последовательность за последний перенесённый номер.
CREATE SEQUENCE malumotnoma_number_seq START WITH 53803;

ALTER TABLE malumotnoma ADD COLUMN number BIGINT;
UPDATE malumotnoma m SET number = s.n
FROM (SELECT id, nextval('malumotnoma_number_seq') AS n
      FROM (SELECT id FROM malumotnoma ORDER BY created_at, id) o) s
WHERE s.id = m.id;
ALTER TABLE malumotnoma ALTER COLUMN number SET NOT NULL;
CREATE UNIQUE INDEX ux_malumotnoma_number ON malumotnoma (number);
CREATE INDEX ix_malumotnoma_fio ON malumotnoma (lower(fio));

-- Перенесена из архива «Роҳхат» (правке не подлежит: цены пересчитаны по действующим тарифам,
-- как их показывает старая система).
ALTER TABLE malumotnoma ADD COLUMN legacy BOOLEAN NOT NULL DEFAULT FALSE;

-- Аннулирование. В старой системе кнопки удаления у справки нет; номер выдан — журнал без дыр.
-- Ошибочную справку администратор аннулирует: она остаётся в журнале, но не входит в отчёт
-- кассира, а проверка по QR показывает «аннулирована».
ALTER TABLE malumotnoma ADD COLUMN annulled_at TIMESTAMPTZ;
ALTER TABLE malumotnoma ADD COLUMN annulled_by VARCHAR(200);
ALTER TABLE malumotnoma ADD COLUMN annul_reason VARCHAR(500);

-- Цена строки на момент выдачи (цена вида × 2 при «туда и обратно», до льготы) — бланк печатал
-- действующий тариф маршрута, и после правки тарифа строки расходились с итогом справки.
ALTER TABLE malumotnoma_line ADD COLUMN price NUMERIC(12,2);
-- Порядок маршрутов в справке (строки упорядочивались по UUID — случайно).
ALTER TABLE malumotnoma_line ADD COLUMN position SMALLINT NOT NULL DEFAULT 0;

UPDATE malumotnoma_line l
SET price = (CASE m.transport_type_id WHEN 1 THEN r.bus_price WHEN 3 THEN r.mbus_price
                                      WHEN 4 THEN r.car_price ELSE 0 END)
            * (CASE WHEN l.round_trip THEN 2 ELSE 1 END)
FROM malumotnoma m, malumotnoma_route r
WHERE m.id = l.malumotnoma_id AND r.id = l.malumotnoma_route_id;

-- id маршрута в routemalumotnomas — ключ переноса справочника и архива справок.
ALTER TABLE malumotnoma_route ADD COLUMN legacy_id INTEGER;
CREATE UNIQUE INDEX ux_malumotnoma_route_legacy ON malumotnoma_route (legacy_id);
