-- Кто ведёт справочники (решение владельца 24.09.2026): сама платформа (MANUAL — как до сих пор) или
-- единая платформа транспорта e-Transport (UNIFIED). При UNIFIED новую компанию / водителя / ТС /
-- сотрудника вручную не завести, а у записей из единой платформы правятся только поля модуля путевых
-- листов. Переключается в Настройки → Интеграции в день подключения. Правило — MasterDataSourcePolicy.
insert into platform_setting (id, category, setting_key, value_type, setting_value, options, name_ru, name_tj, sort_order) values
  (gen_random_uuid(), 'datasource', 'organization', 'ENUM', 'MANUAL', 'MANUAL,UNIFIED',
   'Кто ведёт компании', 'Ширкатҳоро кӣ пеш мебарад', 1),
  (gen_random_uuid(), 'datasource', 'driver', 'ENUM', 'MANUAL', 'MANUAL,UNIFIED',
   'Кто ведёт водителей', 'Ронандагонро кӣ пеш мебарад', 2),
  (gen_random_uuid(), 'datasource', 'vehicle', 'ENUM', 'MANUAL', 'MANUAL,UNIFIED',
   'Кто ведёт транспорт', 'Нақлиётро кӣ пеш мебарад', 3),
  (gen_random_uuid(), 'datasource', 'employee', 'ENUM', 'MANUAL', 'MANUAL,UNIFIED',
   'Кто ведёт сотрудников', 'Кормандонро кӣ пеш мебарад', 4);
