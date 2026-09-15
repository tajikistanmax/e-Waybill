-- Вложения к конкретному путевому листу (скан-копии сопроводительных документов рейса):
-- CMR / международная накладная, ТТН, упаковочный лист, весовой сертификат, скан дозвола,
-- фото груза и т.п. Файлы хранятся в БД (bytea) — самодостаточно, без внешнего хранилища.
CREATE TABLE waybill_attachment (
    id            UUID PRIMARY KEY,
    waybill_id    UUID NOT NULL REFERENCES waybill (id) ON DELETE CASCADE,
    -- CMR | INVOICE | PACKING_LIST | WEIGHT_CERT | PERMIT_SCAN | CARGO_PHOTO | OTHER
    doc_type      VARCHAR(40)  NOT NULL,
    title         VARCHAR(200),
    file_name     VARCHAR(255) NOT NULL,
    content_type  VARCHAR(120) NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    data          BYTEA        NOT NULL,
    uploaded_by   VARCHAR(120),
    uploaded_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_waybill_attachment_waybill ON waybill_attachment (waybill_id);
