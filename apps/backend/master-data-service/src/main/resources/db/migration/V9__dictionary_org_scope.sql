-- Справочники клиентов и маршрутов ведутся ПО ОРГАНИЗАЦИЯМ (каждый перевозчик — свои записи).
-- Раньше number был глобально уникален и не привязан к организации: upsert по findByNumber
-- возвращал ЧУЖУЮ строку, и COMPANY_ADMIN одной организации молча перезаписывал клиента/маршрут
-- другой (кросс-тенантная порча данных). Привязываем запись к организации и делаем номер
-- уникальным В ПРЕДЕЛАХ организации. Таблицы route/client пусты — миграция данных не нужна.

ALTER TABLE route  ADD COLUMN organization_rma VARCHAR(32);
ALTER TABLE client ADD COLUMN organization_rma VARCHAR(32);

-- снять глобальную уникальность номера
ALTER TABLE route  DROP CONSTRAINT route_number_key;
ALTER TABLE client DROP CONSTRAINT client_number_key;

-- организация обязательна (таблицы пусты, запись всегда привязывается контроллером)
ALTER TABLE route  ALTER COLUMN organization_rma SET NOT NULL;
ALTER TABLE client ALTER COLUMN organization_rma SET NOT NULL;

-- уникальность номера — только в пределах организации
CREATE UNIQUE INDEX uq_route_org_number  ON route  (organization_rma, number);
CREATE UNIQUE INDEX uq_client_org_number ON client (organization_rma, number);
