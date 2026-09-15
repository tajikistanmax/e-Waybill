-- Интеграция с единой платформой транспорта Минтранса:
-- субъекты (физлицо/ИП/юрлицо — данные из налоговой по ИНН) и объекты (ТС — данные
-- из базы ГАИ по госномеру) регистрируются ОДИН РАЗ в единой платформе; платформа ЭПД
-- их не регистрирует, а получает по API и хранит реплику (source=UNIFIED, synced_at).

ALTER TABLE organization
    ADD COLUMN subject_type VARCHAR(10) NOT NULL DEFAULT 'LEGAL',   -- PHYSICAL | IP | LEGAL
    ADD COLUMN source       VARCHAR(10) NOT NULL DEFAULT 'MANUAL',  -- MANUAL | UNIFIED
    ADD COLUMN synced_at    TIMESTAMPTZ;

ALTER TABLE driver
    ADD COLUMN source    VARCHAR(10) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN synced_at TIMESTAMPTZ;

ALTER TABLE vehicle
    ADD COLUMN source    VARCHAR(10) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN synced_at TIMESTAMPTZ;

ALTER TABLE employee
    ADD COLUMN source    VARCHAR(10) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN synced_at TIMESTAMPTZ;
