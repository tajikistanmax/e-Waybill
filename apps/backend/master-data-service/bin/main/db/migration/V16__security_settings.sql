-- Настройки безопасности (§29), реально влияющие на поведение. idle_logout_minutes —
-- авто-выход из веб-кабинета после N минут бездействия (0 = выключено). Значение читает
-- веб-клиент (lib/auth) и завершает сессию. Категория security НЕ публичная — только под токеном.
insert into platform_setting (id, category, setting_key, value_type, setting_value, name_ru, name_tj, sort_order) values
  (gen_random_uuid(), 'security', 'idle_logout_minutes', 'NUMBER', '0', 'Авто-выход при бездействии, мин (0 — выкл.)', 'Баромади худкор ҳангоми бефаъолиятӣ, дақ (0 — хомӯш)', 1);
