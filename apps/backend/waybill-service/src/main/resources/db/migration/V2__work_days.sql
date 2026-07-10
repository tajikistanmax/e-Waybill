-- Многодневные путевые листы: рабочие дни и учёт топлива (legacy-формы 1-А/2-Б: по дню на строку).
-- Запись топлива привязывается к ПЛ целиком или к конкретному рабочему дню (work_day_id).

CREATE TABLE work_day (
    id             UUID PRIMARY KEY,
    waybill_id     UUID NOT NULL REFERENCES waybill (id),
    work_date      DATE NOT NULL,
    exit_time      TIME,
    entry_time     TIME,
    odometer_exit  INTEGER,
    odometer_entry INTEGER,
    laps           INTEGER,
    revenue        NUMERIC(12,2),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (waybill_id, work_date)
);
CREATE INDEX idx_work_day_waybill ON work_day (waybill_id);

CREATE TABLE fuel_record (
    id                 UUID PRIMARY KEY,
    waybill_id         UUID NOT NULL REFERENCES waybill (id),
    work_day_id        UUID REFERENCES work_day (id),
    fuel_type          SMALLINT NOT NULL CHECK (fuel_type BETWEEN 1 AND 5), -- 1=Бензин, 2=Солярка, 3=Газ сжиженный, 4=Газ природный, 5=Электро
    fuel_given         NUMERIC(8,2),
    remain_before_exit NUMERIC(8,2),
    remain_entry       NUMERIC(8,2),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_fuel_record_waybill ON fuel_record (waybill_id);
CREATE INDEX idx_fuel_record_work_day ON fuel_record (work_day_id);
