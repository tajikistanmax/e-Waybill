-- Движок бизнес-правил (политик) платформы ЭПД РТ — ядро административной подсистемы «Настройки».
-- Конфигурируемые флаги на 3 уровнях: NATIONAL (умолчание платформы), ORGANIZATION
-- (переопределение перевозчика), VEHICLE_TYPE (переопределение по типу ПЛ).
-- Разрешение (наиболее специфичный побеждает): VEHICLE_TYPE > ORGANIZATION > NATIONAL.
-- Заменяет захардкоженную бизнес-логику (напр. isPassenger()→require_med_post) на конфигурацию.

CREATE TABLE policy (
    id          UUID PRIMARY KEY,
    scope_level VARCHAR(20)  NOT NULL,            -- NATIONAL | ORGANIZATION | VEHICLE_TYPE
    scope_key   VARCHAR(50)  NOT NULL DEFAULT '', -- '' для NATIONAL; РМА для ORGANIZATION; имя типа ПЛ для VEHICLE_TYPE
    rule_key    VARCHAR(50)  NOT NULL,            -- require_med_post, require_tech_check, require_gps, …
    rule_value  VARCHAR(100) NOT NULL,            -- строковое значение (true/false, число)
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_by  VARCHAR(50),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (scope_level, scope_key, rule_key)
);

-- Национальные умолчания (этап 1а).
INSERT INTO policy (id, scope_level, scope_key, rule_key, rule_value) VALUES
    ('44444444-0000-0000-0000-000000000001', 'NATIONAL', '', 'require_med_pre',    'true'),
    ('44444444-0000-0000-0000-000000000002', 'NATIONAL', '', 'require_tech_check', 'true'),
    ('44444444-0000-0000-0000-000000000003', 'NATIONAL', '', 'require_med_post',   'false'),
    ('44444444-0000-0000-0000-000000000004', 'NATIONAL', '', 'require_gps',        'false');

-- Переопределения по типу ПЛ: послерейсовый медосмотр (Т6) обязателен для пассажирских
-- перевозок. Зеркалит прежний WaybillType.isPassenger() (автобус, троллейбус, микроавтобус,
-- такси, международный пассажирский) — поведение close() не меняется, но теперь настраивается.
INSERT INTO policy (id, scope_level, scope_key, rule_key, rule_value) VALUES
    ('44444444-0000-0000-0000-000000000011', 'VEHICLE_TYPE', 'WB_BUS',        'require_med_post', 'true'),
    ('44444444-0000-0000-0000-000000000012', 'VEHICLE_TYPE', 'WB_TROLLEYBUS', 'require_med_post', 'true'),
    ('44444444-0000-0000-0000-000000000013', 'VEHICLE_TYPE', 'WB_MINIBUS',    'require_med_post', 'true'),
    ('44444444-0000-0000-0000-000000000014', 'VEHICLE_TYPE', 'WB_TAXI',       'require_med_post', 'true'),
    ('44444444-0000-0000-0000-000000000015', 'VEHICLE_TYPE', 'WB_PAX_INTL',   'require_med_post', 'true');
