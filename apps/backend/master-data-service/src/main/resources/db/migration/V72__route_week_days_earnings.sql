-- План выручки маршрута по дням недели (legacy routes.week_days_earnings, MIGRATION.md 2.28 / Вопрос 14).
-- В старой платформе поле есть в форме маршрута, но ни в одном расчёте и отчёте не используется —
-- переносим как справочное по решению владельца 22.09 («перенести все поля справочников»).
-- Формат — как в оригинале: JSON вида [{"week_day":1,"earning":1200}, ...] (1 = понедельник).

ALTER TABLE route ADD COLUMN week_days_earnings TEXT;

COMMENT ON COLUMN route.week_days_earnings IS
    'План выручки по дням недели, JSON [{"week_day":1..7,"earning":число}] (legacy routes.week_days_earnings; справочное поле)';
