-- Тумблеры in-app уведомлений (§29): администратор включает/выключает уведомление организации
-- по типу события ПЛ. Читает waybill-service (NotificationService) перед созданием уведомления;
-- при недоступности/отсутствии — уведомление создаётся (безопасный дефолт «включено»).
-- Ключ notify_<status_в_нижнем_регистре> соответствует статусу WaybillStatusChanged.toStatus().
insert into platform_setting (id, category, setting_key, value_type, setting_value, name_ru, name_tj, sort_order) values
  (gen_random_uuid(), 'notifications', 'notify_med_rejected',  'BOOLEAN', 'true', 'Недопуск по медосмотру (водитель)',   'Роҳ надодан аз рӯи ташхиси тиббӣ',  1),
  (gen_random_uuid(), 'notifications', 'notify_tech_rejected', 'BOOLEAN', 'true', 'Недопуск по техконтролю (ТС)',        'Роҳ надодан аз рӯи назорати техникӣ', 2),
  (gen_random_uuid(), 'notifications', 'notify_ready',         'BOOLEAN', 'true', 'Путевой лист готов к выдаче',          'Роҳхат барои додан тайёр аст',      3),
  (gen_random_uuid(), 'notifications', 'notify_blocked',       'BOOLEAN', 'true', 'Блокировка инспектором',               'Басташавӣ аз ҷониби нозир',          4),
  (gen_random_uuid(), 'notifications', 'notify_expired',       'BOOLEAN', 'true', 'Просрочка путевого листа',             'Мӯҳлати роҳхат гузашт',              5);
