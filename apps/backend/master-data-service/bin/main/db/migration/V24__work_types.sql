-- Виды работ спецтехники (форма 09). Спецтехника учитывается по моточасам и виду работ,
-- а не по маршруту/километражу. Классификатор WORK_TYPE — редактируемый (CRUD классификаторов,
-- SYSTEM_ADMIN); форма создания ПЛ типа «Спецтехника» показывает эти значения выпадающим списком.
insert into classifier (id, category, code, name_ru, name_tj, sort_order, active) values
  (gen_random_uuid(), 'WORK_TYPE', 'WT_PLANNING',    'Планировка',                    'Ҳамворкунӣ',                  1, true),
  (gen_random_uuid(), 'WORK_TYPE', 'WT_EXCAVATION',  'Земляные работы (копка)',       'Корҳои хокӣ (кофтуков)',      2, true),
  (gen_random_uuid(), 'WORK_TYPE', 'WT_LOADING',     'Погрузка-разгрузка',            'Боркунӣ ва борфарорӣ',        3, true),
  (gen_random_uuid(), 'WORK_TYPE', 'WT_LIFTING',     'Подъёмные (кран/автовышка)',    'Корҳои боркашӣ (кран)',       4, true),
  (gen_random_uuid(), 'WORK_TYPE', 'WT_COMPACTION',  'Уплотнение (каток)',            'Зичкунӣ (ғелтак)',            5, true),
  (gen_random_uuid(), 'WORK_TYPE', 'WT_CLEANING',    'Уборка (коммунальная)',         'Тозакунӣ (коммуналӣ)',        6, true),
  (gen_random_uuid(), 'WORK_TYPE', 'WT_SNOW',        'Снегоуборка',                   'Барфрӯбӣ',                    7, true),
  (gen_random_uuid(), 'WORK_TYPE', 'WT_OTHER',       'Прочие работы',                 'Корҳои дигар',                8, true);
