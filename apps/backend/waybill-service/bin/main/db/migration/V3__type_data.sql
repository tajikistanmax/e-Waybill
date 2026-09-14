-- Вариативные поля по типу ПЛ (формы 2-Б, 5Б-БМ, 4М-БМ, 3-С и др.) — JSONB.
ALTER TABLE waybill ADD COLUMN type_data JSONB;
