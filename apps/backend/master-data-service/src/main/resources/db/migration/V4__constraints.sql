-- Целостность справочников (аудит): устраняем расхождение «код ожидает уникальный ключ,
-- а БД его не гарантирует» — иначе upsert по ключу падает NonUniqueResult (500) при дублях.

-- coefficient: код ищет по (kind, name) как по уникальному ключу — закрепляем ограничением.
ALTER TABLE coefficient ADD CONSTRAINT uq_coefficient_kind_name UNIQUE (kind, name);

-- fuel_norm / tariff: UNIQUE(transport_type, brand|fuel_type) в Postgres НЕ запрещает несколько
-- строк с NULL (NULL ≠ NULL), поэтому «норма/тариф по умолчанию» мог задвоиться и findBy…IsNull
-- бросал NonUniqueResult. Частичные уникальные индексы закрывают строки-умолчания.
CREATE UNIQUE INDEX uq_fuel_norm_type_default ON fuel_norm (transport_type) WHERE brand IS NULL;
CREATE UNIQUE INDEX uq_tariff_type_default ON tariff (transport_type) WHERE fuel_type IS NULL;
