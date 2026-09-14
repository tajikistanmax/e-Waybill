-- Учредительные и разрешительные документы организации (свидетельство о регистрации,
-- устав, лицензия перевозчика, справка ИНН, приказ о назначении руководителя и т.д.).
-- Файлы хранятся в БД (bytea) — самодостаточно, без внешнего файлового хранилища;
-- скан-копии обычно 1–10 МБ.
CREATE TABLE organization_document (
    id                UUID PRIMARY KEY,
    organization_rma  VARCHAR(10)  NOT NULL,
    -- REGISTRATION_CERT | CHARTER | CARRIER_LICENSE | TAX_CERT | DIRECTOR_ORDER | BANK_DETAILS | OTHER
    doc_type          VARCHAR(40)  NOT NULL,
    title             VARCHAR(200),
    file_name         VARCHAR(255) NOT NULL,
    content_type      VARCHAR(120) NOT NULL,
    size_bytes        BIGINT       NOT NULL,
    data              BYTEA        NOT NULL,
    uploaded_by       VARCHAR(120),
    uploaded_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_org_document_org ON organization_document (organization_rma);
