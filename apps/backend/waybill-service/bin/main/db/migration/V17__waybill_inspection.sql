-- Акт дорожной проверки путевого листа инспектором.
--
-- Блокировка листа — юридическое действие, оно не может быть «просто текстом»:
-- нужны классифицированное основание, место и время проверки, кто проверял и
-- номер составленного акта. Отдельная таблица (а не поля на waybill) потому, что:
--   * проверка может закончиться БЕЗ блокировки («нарушений нет») — это тоже факт,
--     который нужен для статистики надзора и для защиты перевозчика от повторных
--     придирок на том же рейсе;
--   * за один рейс лист могут проверить несколько раз в разных местах.
--
-- Сама блокировка остаётся статусом ПЛ (BLOCKED) — здесь хранится её обоснование.

CREATE TABLE waybill_inspection (
    id              UUID PRIMARY KEY,
    waybill_id      UUID         NOT NULL REFERENCES waybill (id) ON DELETE CASCADE,
    -- PASSED — проверено, нарушений нет; BLOCKED — лист заблокирован по основанию ниже
    action          VARCHAR(16)  NOT NULL,
    -- Классификатор оснований (см. InspectionReason): NO_MED, NO_TECH, EXPIRED,
    -- DRIVER_MISMATCH, VEHICLE_MISMATCH, NO_DOCUMENT, LICENSE, CARGO, REGIME,
    -- TECH_CONDITION, OTHER. Для PASSED — NULL.
    reason_code     VARCHAR(32),
    description     TEXT,
    -- Место остановки: адрес / пост / км трассы, как записал инспектор
    place           VARCHAR(255),
    lat             NUMERIC(9,6),
    lon             NUMERIC(9,6),
    -- Номер бумажного акта/протокола, если он составлен
    protocol_number VARCHAR(64),
    inspector_rma   VARCHAR(10)  NOT NULL,
    inspector_name  VARCHAR(160),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_waybill_inspection_waybill ON waybill_inspection (waybill_id, created_at DESC);
CREATE INDEX idx_waybill_inspection_created ON waybill_inspection (created_at DESC);
CREATE INDEX idx_waybill_inspection_inspector ON waybill_inspection (inspector_rma, created_at DESC);
