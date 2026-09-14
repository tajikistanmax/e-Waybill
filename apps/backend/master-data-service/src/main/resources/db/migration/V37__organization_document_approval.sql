-- Процедура одобрения учредительных документов организации (аналог subject_document):
-- админ компании прикрепляет (PENDING), Минтранс (SYSTEM_ADMIN) сверяет с ЕГРЮЛ / реестром
-- лицензий и одобряет (APPROVED) либо отклоняет (REJECTED) с примечанием.
ALTER TABLE organization_document
    ADD COLUMN status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    ADD COLUMN review_note VARCHAR(300),
    ADD COLUMN reviewed_by VARCHAR(120),
    ADD COLUMN reviewed_at TIMESTAMPTZ;

-- Документы, загруженные до появления процедуры, считаем уже принятыми.
UPDATE organization_document SET status = 'APPROVED' WHERE reviewed_at IS NULL;
