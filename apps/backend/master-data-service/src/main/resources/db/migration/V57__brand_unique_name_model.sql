-- Марки ТС: в боевом уникальна пара (марка+модель), а не имя. Ослабляем индекс.
DROP INDEX IF EXISTS uq_brand_name;
CREATE UNIQUE INDEX IF NOT EXISTS uq_brand_name_model ON brand (lower(name), lower(model));
