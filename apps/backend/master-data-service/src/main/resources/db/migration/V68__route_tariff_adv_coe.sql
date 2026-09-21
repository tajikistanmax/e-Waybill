-- MIGRATION.md 2.26: tariffs.adv_coe («Коэффитсиенти иловагӣ» — дополнительный коэффициент тарифа маршрута).
-- В legacy — только поле CRUD (TariffCrudController), в расчётах/отчётах не используется; переносится как справочное.
ALTER TABLE route_tariff ADD COLUMN IF NOT EXISTS adv_coe NUMERIC(10, 4);
