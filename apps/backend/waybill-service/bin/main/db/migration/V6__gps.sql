-- Приём GPS-координат транспортных средств: устройство/трекер шлёт позиции,
-- платформа хранит их и отдаёт последнюю позицию и трек по путевому листу.
-- Одна строка = один пинг (замер) положения ТС.

CREATE TABLE gps_ping (
    id                 UUID PRIMARY KEY,
    waybill_id         UUID,                    -- связанный путевой лист (может быть null)
    vehicle_reg_number VARCHAR(20)  NOT NULL,   -- госномер ТС (канонизирован: trim + upper)
    lat                NUMERIC(9,6) NOT NULL,   -- широта
    lon                NUMERIC(9,6) NOT NULL,   -- долгота
    speed_kmh          SMALLINT,                -- скорость, км/ч (может быть null)
    recorded_at        TIMESTAMPTZ  NOT NULL,   -- время замера на устройстве
    received_at        TIMESTAMPTZ  NOT NULL DEFAULT now()  -- время приёма платформой
);

-- Последняя позиция по госномеру ТС (recorded_at DESC — свежие сверху).
CREATE INDEX idx_gps_ping_vehicle ON gps_ping (vehicle_reg_number, recorded_at DESC);

-- Трек по путевому листу (recorded_at ASC — хронологический порядок).
CREATE INDEX idx_gps_ping_waybill ON gps_ping (waybill_id, recorded_at);
