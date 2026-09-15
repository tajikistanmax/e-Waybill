-- Часть 1: справочники расчётного ядра (V27/V28, LegacyReferenceController) получают
-- write-путь (POST upsert-by-natural-key, SYSTEM_ADMIN). Как и в V4__constraints.sql —
-- «код ищет по (ключу) как по уникальному, а БД его не гарантирует» приводит к
-- NonUniqueResult (500) при дублях. Закрепляем ограничениями до включения записи.
-- Существующие сиды (V27/V28) уже уникальны по этим ключам — конфликтов нет.

CREATE UNIQUE INDEX uq_brand_name            ON brand            (lower(name));
CREATE UNIQUE INDEX uq_fuel_winter_coef_name ON fuel_winter_coef (lower(name));
CREATE UNIQUE INDEX uq_mountain_coef_name    ON mountain_coef    (lower(name));
CREATE UNIQUE INDEX uq_city_coef_name        ON city_coef        (lower(name));
CREATE UNIQUE INDEX uq_drive_class_class     ON drive_class      (lower(class));
CREATE UNIQUE INDEX uq_direction_title       ON direction        (lower(title));

-- used_coef: естественный ключ — пара (year, km) (возраст/пробег, при превышении которых
-- начисляется надбавка coef). NULL в паре допускается схемой, но апсерт API требует оба поля.
ALTER TABLE used_coef ADD CONSTRAINT uq_used_coef_year_km UNIQUE (year, km);

-- route_tariff: естественный ключ — (route_id, fuel_id); fuel_id = NULL — тариф «для всех видов
-- топлива» маршрута, поэтому нужен частичный индекс (тот же приём, что и uq_fuel_norm_type_default
-- / uq_tariff_type_default в V4__constraints.sql — NULL ≠ NULL в обычном UNIQUE).
CREATE UNIQUE INDEX uq_route_tariff_route_fuel    ON route_tariff (route_id, fuel_id) WHERE fuel_id IS NOT NULL;
CREATE UNIQUE INDEX uq_route_tariff_route_default ON route_tariff (route_id)          WHERE fuel_id IS NULL;

-- Часть 2: новый справочник «Груз» (бор) — перенос cargos из ИС «Роҳхат»
-- (database/migrations/2022_06_24_204806_create_cargo_table.php). Платформенный (не
-- организация-скоуп): в эталоне cargos не имеет колонки компании/организации, правит
-- COMPANY_ADMIN/SYSTEM_ADMIN (как Client), один общий справочник для всех перевозчиков.
CREATE TABLE cargo (
    id          UUID          PRIMARY KEY,
    name        VARCHAR(150)  NOT NULL,
    type        VARCHAR(50),
    unit        VARCHAR(50),
    price       NUMERIC(10, 2),
    cargo_class SMALLINT
);
CREATE UNIQUE INDEX uq_cargo_name ON cargo (lower(name));
