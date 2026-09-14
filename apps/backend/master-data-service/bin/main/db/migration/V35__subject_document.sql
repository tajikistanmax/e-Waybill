-- Документы транспортных средств и водителей (скан-копии) с процедурой одобрения.
-- Диспетчер/админ компании прикрепляет документ (статус PENDING), админ компании
-- или сисадмин одобряет (APPROVED) либо отклоняет (REJECTED). Файлы в БД (bytea).
CREATE TABLE subject_document (
    id             UUID PRIMARY KEY,
    -- VEHICLE | DRIVER
    subject_type   VARCHAR(16)  NOT NULL,
    -- госномер ТС либо РМА водителя
    subject_key    VARCHAR(32)  NOT NULL,
    organization_rma VARCHAR(10),
    -- VEHICLE: TECH_PASSPORT | INSURANCE | TECH_INSPECTION | LEASE_CONTRACT | ADR_CERT | OTHER
    -- DRIVER:  DRIVER_LICENSE | MED_CERT | SAFETY_COURSE | ADR_CERT | PASSPORT | OTHER
    doc_type       VARCHAR(40)  NOT NULL,
    title          VARCHAR(200),
    valid_to       DATE,
    file_name      VARCHAR(255) NOT NULL,
    content_type   VARCHAR(120) NOT NULL,
    size_bytes     BIGINT       NOT NULL,
    data           BYTEA        NOT NULL,
    -- PENDING | APPROVED | REJECTED
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    review_note    VARCHAR(300),
    uploaded_by    VARCHAR(120),
    uploaded_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reviewed_by    VARCHAR(120),
    reviewed_at    TIMESTAMPTZ
);
CREATE INDEX ix_subject_document_subject ON subject_document (subject_type, subject_key);
CREATE INDEX ix_subject_document_org ON subject_document (organization_rma);
