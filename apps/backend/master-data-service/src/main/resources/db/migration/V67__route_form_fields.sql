-- MIGRATION.md 2.28: поля формы маршрута legacy (routes), не участвующие в расчёте/печати:
-- name_a/name_b (Номгӯи хатсайр A/B — конечные пункты), time_one_lap_a/b (время одного рейса),
-- valid_cert (срок свидетельства), city (Шаҳру ноҳия — имя из справочника city), latitude/longitude.
-- week_days_earnings НЕ переносится (в legacy нигде не используется — MIGRATION.md Вопрос 14).
ALTER TABLE route ADD COLUMN IF NOT EXISTS name_a          VARCHAR(100);
ALTER TABLE route ADD COLUMN IF NOT EXISTS name_b          VARCHAR(100);
ALTER TABLE route ADD COLUMN IF NOT EXISTS time_one_lap_a  TIME;
ALTER TABLE route ADD COLUMN IF NOT EXISTS time_one_lap_b  TIME;
ALTER TABLE route ADD COLUMN IF NOT EXISTS valid_cert      DATE;
ALTER TABLE route ADD COLUMN IF NOT EXISTS city_name       VARCHAR(200);
ALTER TABLE route ADD COLUMN IF NOT EXISTS latitude        NUMERIC(10, 6);
ALTER TABLE route ADD COLUMN IF NOT EXISTS longitude       NUMERIC(10, 6);
