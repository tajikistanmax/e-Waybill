-- Иерархия организаций: компания → филиалы.
--
-- Филиал — самостоятельная операционная единица: свой транспорт, водители,
-- сотрудники, путевые листы и свой кабинет, который видит только своё.
-- Структуру зеркалим из единой платформы e-Transport: parent приходит при
-- /sync/organization, вручную в модуле филиал не создаётся.
--
-- parent_rma = NULL — головная компания либо самостоятельная организация (филиалов нет).

ALTER TABLE organization ADD COLUMN parent_rma VARCHAR(10);

ALTER TABLE organization
    ADD CONSTRAINT fk_organization_parent
    FOREIGN KEY (parent_rma) REFERENCES organization (rma)
    ON DELETE SET NULL;

CREATE INDEX idx_organization_parent_rma ON organization (parent_rma);

COMMENT ON COLUMN organization.parent_rma IS
    'РМА головной компании; NULL — головная либо самостоятельная организация';
