-- Названия типов путевых листов (редактируемые администратором). Хранятся как классификатор
-- категории WAYBILL_TYPE (code = enum WaybillType). Отображение (tType) берёт их отсюда, иначе —
-- зашитый дефолт. СТРУКТУРНЫЕ параметры типа (форма/нац.код/срок) остаются в enum — редактируется
-- только НАЗВАНИЕ. Редактирование — SYSTEM_ADMIN (через существующий CRUD классификаторов).
insert into classifier (id, category, code, name_ru, name_tj, sort_order, active) values
  (gen_random_uuid(), 'WAYBILL_TYPE', 'WB_CAR',        'Легковой (3-С)',                  'Сабукрав (3-С)',                    1, true),
  (gen_random_uuid(), 'WAYBILL_TYPE', 'WB_TAXI',       'Такси (3-С)',                     'Такси (3-С)',                       2, true),
  (gen_random_uuid(), 'WAYBILL_TYPE', 'WB_MINIBUS',    'Микроавтобус (1-А)',              'Микроавтобус (1-А)',                3, true),
  (gen_random_uuid(), 'WAYBILL_TYPE', 'WB_BUS',        'Автобус Т(1-АД)',                 'Автобус Т(1-АД)',                   4, true),
  (gen_random_uuid(), 'WAYBILL_TYPE', 'WB_TROLLEYBUS', 'Троллейбус Т(1-АД)',              'Троллейбус Т(1-АД)',                5, true),
  (gen_random_uuid(), 'WAYBILL_TYPE', 'WB_TRUCK',      'Грузовой (2-Б)',                  'Боркаш (2-Б)',                      6, true),
  (gen_random_uuid(), 'WAYBILL_TYPE', 'WB_TRUCK_INTL', 'Грузовой международный (5Б-БМ)',  'Боркаши байналмилалӣ (5Б-БМ)',      7, true),
  (gen_random_uuid(), 'WAYBILL_TYPE', 'WB_PAX_INTL',   'Пассажирский международный (4М-БМ)', 'Мусофирбари байналмилалӣ (4М-БМ)', 8, true),
  (gen_random_uuid(), 'WAYBILL_TYPE', 'WB_SPECIAL',    'Спецтехника',                     'Техникаи махсус',                   9, true),
  (gen_random_uuid(), 'WAYBILL_TYPE', 'WB_DANGEROUS',  'Опасные грузы',                   'Борҳои хатарнок',                  10, true);
