-- Справочники платформы ЭПД РТ (Этап 1б): маршруты, клиенты, нормы расхода,
-- коэффициенты и нархнома (прейскурант). Соответствуют массивам старой программы
-- (spec/notes/02, раздел 3): нужны для нормирования расхода топлива и расчёта стоимости.

CREATE TABLE route (
    id             UUID PRIMARY KEY,
    number         VARCHAR(10)  NOT NULL,
    name           VARCHAR(300) NOT NULL,
    transport_type SMALLINT,               -- 1=автобус,2=троллейбус,3=микроавтобус…
    region_id      SMALLINT,               -- 1..7
    UNIQUE (number)
);

CREATE TABLE client (
    id      UUID PRIMARY KEY,
    number  VARCHAR(10)  UNIQUE,
    name    VARCHAR(300) NOT NULL,
    address VARCHAR(500),
    phone   VARCHAR(50)
);

-- Норма расхода топлива, л/100км. brand=NULL — норма для всех марок данного типа ТС.
CREATE TABLE fuel_norm (
    id             UUID PRIMARY KEY,
    transport_type SMALLINT     NOT NULL,
    brand          VARCHAR(200),
    base_norm      NUMERIC(6, 2) NOT NULL,
    UNIQUE (transport_type, brand)
);

-- Коэффициенты нормирования: WINTER (зимний, окно месяцев), CITY (внутригородской),
-- HIGHLAND (высокогорный, по региону), USAGE (эксплуатационный/износа, TODO).
CREATE TABLE coefficient (
    id         UUID PRIMARY KEY,
    kind       VARCHAR(20)  NOT NULL,       -- WINTER|CITY|HIGHLAND|USAGE
    name       VARCHAR(200) NOT NULL,
    value      NUMERIC(5, 3) NOT NULL,      -- множитель, напр. 1.100
    region_id  SMALLINT,
    month_from SMALLINT,                    -- для WINTER, напр. 11..3 (переход через год)
    month_to   SMALLINT
);

-- Нархнома (прейскурант): тариф за 1 км. fuel_type=NULL — тариф для всех видов топлива.
CREATE TABLE tariff (
    id             UUID PRIMARY KEY,
    transport_type SMALLINT     NOT NULL,
    fuel_type      SMALLINT,
    price_per_km   NUMERIC(8, 2) NOT NULL,  -- сомони/км
    UNIQUE (transport_type, fuel_type)
);

-- Сиды: нормы расхода (л/100км). Троллейбус (тип 2) — электротяга, пропущен.
INSERT INTO fuel_norm (id, transport_type, brand, base_norm) VALUES
    ('11111111-0000-0000-0000-000000000001', 1, NULL, 32.0),  -- автобус
    ('11111111-0000-0000-0000-000000000003', 3, NULL, 14.0),  -- микроавтобус
    ('11111111-0000-0000-0000-000000000004', 4, NULL, 9.5),   -- легковой
    ('11111111-0000-0000-0000-000000000005', 5, NULL, 25.0),  -- грузовой
    ('11111111-0000-0000-0000-000000000006', 6, NULL, 28.0);  -- грузовой международный

-- Сиды: коэффициенты нормирования.
INSERT INTO coefficient (id, kind, name, value, region_id, month_from, month_to) VALUES
    ('22222222-0000-0000-0000-000000000001', 'WINTER',   'Зимний (ноябрь–март)', 1.100, NULL, 11, 3),
    ('22222222-0000-0000-0000-000000000002', 'CITY',     'Внутригородской',      1.050, NULL, NULL, NULL),
    ('22222222-0000-0000-0000-000000000003', 'HIGHLAND', 'Высокогорный (ГБАО)',  1.150, 2,    NULL, NULL);

-- Сиды: нархнома (сомони/км).
INSERT INTO tariff (id, transport_type, fuel_type, price_per_km) VALUES
    ('33333333-0000-0000-0000-000000000001', 1, NULL, 2.50),  -- автобус
    ('33333333-0000-0000-0000-000000000004', 4, NULL, 1.20),  -- легковой
    ('33333333-0000-0000-0000-000000000005', 5, NULL, 3.00);  -- грузовой
