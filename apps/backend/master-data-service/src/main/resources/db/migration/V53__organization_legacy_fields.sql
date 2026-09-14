-- Паритет карточки компании с боевой формой MinTransRT (company/create): скалярные реквизиты.
-- «Связанные компании для осмотра» (many-to-many) сознательно не переносим.
ALTER TABLE organization
    ADD COLUMN ownership                SMALLINT,       -- намуди моликият: 1=частная(Шахси), 2=государственная(Давлатӣ)
    ADD COLUMN latitude                 NUMERIC(10, 7), -- широта (для карты)
    ADD COLUMN longitude                NUMERIC(10, 7), -- долгота
    ADD COLUMN registration_cert_number VARCHAR(100),   -- № свидетельства о регистрации предприятия
    ADD COLUMN extract_number           VARCHAR(100),   -- иқтибос (№ выписки)
    ADD COLUMN vat_cert_number          VARCHAR(100),   -- № свидетельства ААИ/НДС (18%)
    ADD COLUMN plan_pass_volume         NUMERIC(14, 2), -- план: объём перевозок, тыс. пасс.
    ADD COLUMN plan_pass_traffic        NUMERIC(14, 2); -- план: пассажирооборот, млн пасс.

ALTER TABLE organization
    ADD CONSTRAINT organization_ownership_range
        CHECK (ownership IS NULL OR ownership IN (1, 2));

COMMENT ON COLUMN organization.ownership IS 'Форма собственности: 1=частная (Шахсӣ), 2=государственная (Давлатӣ)';
COMMENT ON COLUMN organization.registration_cert_number IS 'Свидетельство о регистрации предприятия (боевое registration_certificate)';
COMMENT ON COLUMN organization.extract_number IS 'Выписка (иқтибос)';
COMMENT ON COLUMN organization.vat_cert_number IS 'Свидетельство ААИ/НДС 18% (боевое aai)';
