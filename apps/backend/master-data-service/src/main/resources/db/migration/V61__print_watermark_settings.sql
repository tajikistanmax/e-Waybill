-- Водяной знак и отметка о формировании печатных бланков ПЛ (B4, НЕ-ЭЦП часть).
-- Две настройки категории «print» (та же таблица platform_setting и формат, что у
-- show_qr / show_stamp / paper_size из V10): тумблер печати водяного знака и его текст.
-- Настройки самоописываемы — редактор печати (/settings/print, SettingsEditor категории
-- print) отрисует их обобщённо, править веб-код не нужно. Изображение ЭЦП здесь НЕ
-- вводится (ждёт боевой CAdES/УЦ РТ — отдельный отложенный пункт).
-- Идемпотентно (ON CONFLICT (category, setting_key) DO NOTHING по uq_platform_setting).
insert into platform_setting (id, category, setting_key, value_type, setting_value, options, name_ru, name_tj, sort_order) values
  (gen_random_uuid(), 'print', 'show_watermark', 'BOOLEAN', 'false', null,                     'Печатать водяной знак', 'Чоп кардани аломати обӣ', 5),
  (gen_random_uuid(), 'print', 'watermark_text', 'STRING',  'ЭЛЕКТРОННЫЙ ДОКУМЕНТ', null,       'Текст водяного знака',  'Матни аломати обӣ',       6)
on conflict (category, setting_key) do nothing;
