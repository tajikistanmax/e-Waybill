-- Названия статусов путевых листов (редактируемые администратором). Хранятся как классификатор
-- категории WAYBILL_STATUS (code = enum WaybillStatus waybill-service). Отображение (tStatus)
-- берёт их отсюда, иначе — зашитый дефолт. САМА статусная машина (переходы) остаётся в коде —
-- редактируется только НАЗВАНИЕ. Редактирование — SYSTEM_ADMIN (существующий CRUD классификаторов).
insert into classifier (id, category, code, name_ru, name_tj, sort_order, active) values
  (gen_random_uuid(), 'WAYBILL_STATUS', 'DRAFT',            'Черновик',            'Сиёҳнавис',                 1, true),
  (gen_random_uuid(), 'WAYBILL_STATUS', 'CREATED',          'Ожидает осмотров',    'Дар интизори ташхисҳо',     2, true),
  (gen_random_uuid(), 'WAYBILL_STATUS', 'MED_REJECTED',     'Медосмотр отклонён',  'Ташхиси тиббӣ рад шуд',     3, true),
  (gen_random_uuid(), 'WAYBILL_STATUS', 'TECH_REJECTED',    'Техосмотр отклонён',  'Назорати техникӣ рад шуд',  4, true),
  (gen_random_uuid(), 'WAYBILL_STATUS', 'AWAITING_PAYMENT', 'Ожидает оплаты',      'Дар интизори пардохт',      5, true),
  (gen_random_uuid(), 'WAYBILL_STATUS', 'PAID',             'Оплачен',             'Пардохта шуд',              6, true),
  (gen_random_uuid(), 'WAYBILL_STATUS', 'READY',            'Готов к выдаче',      'Барои додан тайёр',         7, true),
  (gen_random_uuid(), 'WAYBILL_STATUS', 'ISSUED',           'Выдан',               'Дода шуд',                  8, true),
  (gen_random_uuid(), 'WAYBILL_STATUS', 'ACTIVE',           'Активен',             'Фаъол',                     9, true),
  (gen_random_uuid(), 'WAYBILL_STATUS', 'RETURNED',         'Возвращён',           'Баргардонида шуд',         10, true),
  (gen_random_uuid(), 'WAYBILL_STATUS', 'COMPLETED',        'Завершён',            'Анҷом ёфт',                11, true),
  (gen_random_uuid(), 'WAYBILL_STATUS', 'CANCELLED',        'Аннулирован',         'Бекор шуд',                12, true),
  (gen_random_uuid(), 'WAYBILL_STATUS', 'EXPIRED',          'Просрочен',           'Мӯҳлаташ гузашт',          13, true),
  (gen_random_uuid(), 'WAYBILL_STATUS', 'BLOCKED',          'Заблокирован',        'Баста шуд',                14, true),
  (gen_random_uuid(), 'WAYBILL_STATUS', 'ARCHIVED',         'В архиве',            'Дар бойгонӣ',              15, true);
