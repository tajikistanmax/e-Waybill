-- Мастер-данные платформы ЭПД РТ (walking skeleton, Этап 1а)
-- Бизнес-ключи: РМА (организации, водители, сотрудники), госномер (ТС). PK — UUID.

CREATE TABLE organization (
    id               UUID PRIMARY KEY,
    rma              VARCHAR(10)  NOT NULL UNIQUE,
    kpp              VARCHAR(20),
    name             VARCHAR(500) NOT NULL,
    type_company     SMALLINT     NOT NULL DEFAULT 1, -- 1=общего пользования, 2=ведомственный
    region_id        SMALLINT,                        -- 1..7
    city_name        VARCHAR(200),
    address          VARCHAR(500),
    phone            VARCHAR(50),
    email            VARCHAR(200),
    name_head        VARCHAR(300),
    bank             VARCHAR(300),
    license_from     DATE,
    license_to       DATE,
    blocked          BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE driver (
    id                    UUID PRIMARY KEY,
    rma                   VARCHAR(10)  NOT NULL UNIQUE,
    organization_id       UUID REFERENCES organization (id),
    tab_number            VARCHAR(10),
    full_name             VARCHAR(300) NOT NULL,
    license_number        VARCHAR(50),
    license_categories    VARCHAR(30),
    license_valid_to      DATE,
    degree                SMALLINT, -- класс водителя: 1,2,3
    med_cert_number       VARCHAR(50),
    med_cert_valid_to     DATE,
    safety_course_valid_to DATE,    -- 20-часовые занятия
    phone                 VARCHAR(50),
    suspended             BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_driver_org ON driver (organization_id);

CREATE TABLE vehicle (
    id                       UUID PRIMARY KEY,
    registration_number      VARCHAR(20)  NOT NULL UNIQUE,
    organization_id          UUID REFERENCES organization (id),
    transport_type           SMALLINT     NOT NULL, -- 1=автобус,2=троллейбус,3=микроавтобус,4=легковой,5=грузовой,6=грузовой межд.
    brand                    VARCHAR(200),
    parking_number           VARCHAR(4),            -- стоянка: 1-я цифра колонна, 3-4-я бригада
    capacity                 INTEGER,
    carrying                 NUMERIC(10, 2),
    odometer                 INTEGER      NOT NULL DEFAULT 0,
    vincode                  VARCHAR(50),
    year_manufacture         SMALLINT,
    tech_inspection_valid_to DATE,
    control_card_valid_to    DATE,                  -- варақаи назоратӣ
    blocked                  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_vehicle_org ON vehicle (organization_id);

CREATE TABLE employee (
    id              UUID PRIMARY KEY,
    rma             VARCHAR(10)  NOT NULL UNIQUE,
    organization_id UUID REFERENCES organization (id),
    tab_number      VARCHAR(10),
    name            VARCHAR(300) NOT NULL,
    type            SMALLINT     NOT NULL, -- 1=врач (духтур), 2=механик, 3=диспетчер (танзимгар)
    phone           VARCHAR(50),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_employee_org ON employee (organization_id);
