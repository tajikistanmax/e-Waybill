-- Новый тип ПЛ «Мотоцикл (аренда)» = classifier WAYBILL_TYPE (редактируемое имя; структура типа — в enum WaybillType).
insert into classifier (id, category, code, name_ru, name_tj, sort_order, active) values
  (gen_random_uuid(), 'WAYBILL_TYPE', 'WB_MOTORCYCLE', 'Мотоцикл (аренда)', 'Мотосикл (иҷора)', 11, true);

-- Новый вид ТС transport_type = 7 (мотоцикл): национальная норма топлива (бензин), л/100км.
insert into fuel_norm (id, transport_type, brand, base_norm, unit) values
  ('11111111-0000-0000-0000-000000000007', 7, NULL, 3.50, 'KM');
