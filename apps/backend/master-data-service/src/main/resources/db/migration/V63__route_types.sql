-- Справочник типов маршрутов — «навъҳои хатсайр» (аналог RouteType из ИС «Роҳхат»).
-- Классифицирует пассажирский маршрут (route) по дальности/характеру сообщения:
-- городской, пригородный, междугородный, международный, транзитный.
--
-- Ключ справочника — числовой code (человеко-понятный естественный ключ), по образцу
-- региона (region, V62). Связь с маршрутом «мягкая» — по route.route_type_code,
-- жёсткого FK намеренно нет (тот же приём, что и у city.region_id / route.region_id),
-- чтобы не ломать существующие данные маршрутов.
-- Двуязычное наименование: name_ru обязательно, name_tj — тадж. эквивалент.
CREATE TABLE route_type (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code       SMALLINT     NOT NULL,
    name_ru    VARCHAR(200) NOT NULL,
    name_tj    VARCHAR(200),
    sort_order SMALLINT     NOT NULL DEFAULT 0,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_route_type_code UNIQUE (code)
);

-- Сид базовых типов маршрутов. Идемпотентно: ON CONFLICT (code) DO NOTHING.
-- name_tj — таджикский эквивалент.
INSERT INTO route_type (code, name_ru, name_tj, sort_order) VALUES
 (1,'Городской','Шаҳрӣ',1),
 (2,'Пригородный','Наздишаҳрӣ',2),
 (3,'Междугородный','Байнишаҳрӣ',3),
 (4,'Международный','Байналмилалӣ',4),
 (5,'Транзитный','Транзитӣ',5)
ON CONFLICT (code) DO NOTHING;

-- Типизация маршрута: «мягкий» код типа (nullable — у старых маршрутов тип не задан).
-- Жёсткого FK на route_type намеренно нет (как у route.region_id).
ALTER TABLE route ADD COLUMN IF NOT EXISTS route_type_code SMALLINT;
