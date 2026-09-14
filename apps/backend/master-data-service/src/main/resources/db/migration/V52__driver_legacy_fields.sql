-- Паритет карточки водителя с боевой формой MinTransRT (driver/create): недостающие реквизиты
-- + закрепление ТС за водителем (для колонки «Номер транспорта» в реестре водителей).
-- Копия ВУ ведётся через существующую подсистему «документы водителя» (MinIO), отдельным полем не хранится.
ALTER TABLE driver
    ADD COLUMN passport            VARCHAR(50),   -- шиносномаи ронанда (паспорт водителя)
    ADD COLUMN address             VARCHAR(300),  -- суроға (адрес)
    ADD COLUMN email               VARCHAR(150),  -- почтаи электронӣ
    ADD COLUMN power_attorney      VARCHAR(100),  -- ваколатнома (доверенность)
    ADD COLUMN visa_valid_to       DATE,          -- муҳлати виза (срок визы)
    ADD COLUMN contract_number     VARCHAR(50),   -- рақами шартнома (№ договора)
    ADD COLUMN contract_valid_to   DATE,          -- муҳлати шартнома то (договор действ. до)
    ADD COLUMN assigned_vehicle_id UUID REFERENCES vehicle (id) ON DELETE SET NULL; -- закреплённое ТС

CREATE INDEX idx_driver_assigned_vehicle ON driver (assigned_vehicle_id);

COMMENT ON COLUMN driver.passport IS 'Паспорт водителя (шиносномаи ронанда) — из боевой driver/create';
COMMENT ON COLUMN driver.power_attorney IS 'Доверенность (ваколатнома)';
COMMENT ON COLUMN driver.assigned_vehicle_id IS 'Закреплённое ТС (аналог vehicle.timesheet в боевой); показывается в реестре водителей';
