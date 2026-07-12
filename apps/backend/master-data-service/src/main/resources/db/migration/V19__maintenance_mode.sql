-- Режим технических работ (§29): при включении во всём кабинете и на странице входа показывается
-- предупреждающий баннер. Категория general — публичная (баннер читается без токена, в т.ч. на входе).
-- Администратор переключает на странице «Настройки → Общие» (SettingsEditor рендерит BOOLEAN-тумблер).
insert into platform_setting (id, category, setting_key, value_type, setting_value, name_ru, name_tj, sort_order) values
  (gen_random_uuid(), 'general', 'maintenance_mode', 'BOOLEAN', 'false',
   'Режим технических работ (баннер)', 'Реҷаи корҳои техникӣ (баннер)', 20);
