-- Разрешённые организации типы путевых листов (аналог per-user permissions
-- bus/ebus/mbus/taxi/cargo2b/cargo5bbm из ИС «Роҳхат»): CSV имён WaybillType.
-- NULL — ограничение только по виду субъекта (type_company) и лицензии, как раньше.
--
-- Пример: транспортная компания с лицензией только на грузовые перевозки не может
-- выписать пассажирский ПЛ, компания легковых такси — только 3-С / такси, и т. д.

ALTER TABLE organization ADD COLUMN allowed_waybill_types VARCHAR(300);

-- Демо-профили стенда.
UPDATE organization SET allowed_waybill_types = 'WB_TRUCK,WB_TRUCK_INTL,WB_SPECIAL,WB_DANGEROUS'
 WHERE rma = '100003000';   -- «Боркашонии Суғд» — только грузовые
UPDATE organization SET allowed_waybill_types = 'WB_CAR,WB_TAXI,WB_MINIBUS,WB_BUS'
 WHERE rma IN ('100001000', '100004000');   -- пассажирские перевозчики
-- 100002000 — без ограничения (NULL), может всё в рамках лицензии
