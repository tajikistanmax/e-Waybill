-- Борхатҳо (накладные замимаи 1 / 2) к путевому листу 2-Б — отдельная сущность, N на лист, как в
-- legacy cargo_waybills (у 20 878 листов больше одного борхата, до 377). У каждого борхата свои номер,
-- дата, плательщик («Мизоҷ»), стороны, груз, количество, расстояние, число рейсов, масса; из строк
-- собираются транспортная работа P и число ездок Z расчёта (legacy CargoFuelBase::calcP/calcZ).
-- До этой миграции на лист была одна «накладная» в type_data, P и Z вводились вручную при возврате.
CREATE SEQUENCE consignment_note_number_seq START 1;

CREATE TABLE consignment_note (
    id               UUID PRIMARY KEY,
    waybill_id       UUID         NOT NULL REFERENCES waybill (id),
    work_day_id      UUID         REFERENCES work_day (id) ON DELETE SET NULL,
    kind             SMALLINT     NOT NULL DEFAULT 1,        -- 1 — замимаи 1, 2 — замимаи 2 (с экспедитором)
    number           BIGINT       NOT NULL DEFAULT nextval('consignment_note_number_seq'),
    note_date        DATE         NOT NULL,
    payer_id         UUID,                                    -- «Мизоҷ» (фармоишгар, плательщик)
    payer_name       VARCHAR(300),
    sender_id        UUID,
    sender_name      VARCHAR(300),
    sender_address   VARCHAR(500),
    receiver_id      UUID,
    receiver_name    VARCHAR(300),
    receiver_address VARCHAR(500),
    forwarder_id     UUID,
    forwarder_name   VARCHAR(300),
    cargo_id         UUID,
    cargo_name       VARCHAR(300),
    cargo_number     BIGINT,
    cargo_amount     NUMERIC(14, 3),                          -- «Миқдори бор» (количество, в единицах груза)
    cargo_weight     NUMERIC(14, 3),                          -- «Ҳаҷми бор (тн)»
    distance         NUMERIC(10, 2),                          -- «Масофа (км)»
    trips            INTEGER      NOT NULL DEFAULT 1,         -- «Шумораи рейс»
    special_distance NUMERIC(10, 2),                          -- «Масофаи иҷроиши кори махсус (км)»
    entry_time       TIMESTAMPTZ,                             -- «Сана ва вақти даромад»
    invoice_number   VARCHAR(64),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by       VARCHAR(100)
);

CREATE INDEX ix_consignment_note_waybill ON consignment_note (waybill_id);
CREATE INDEX ix_consignment_note_date ON consignment_note (note_date);
CREATE UNIQUE INDEX uq_consignment_note_number ON consignment_note (number);
