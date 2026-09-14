-- Свидетельства ADR (опасные грузы, ДОПОГ): водитель — ДОПОГ-свидетельство, ТС — свидетельство
-- о допуске к перевозке опасных грузов. Обязательны для типа ПЛ «Опасные грузы» (checks.yaml id 19).
-- Nullable: у обычных водителей/ТС ADR отсутствует и не требуется.
ALTER TABLE driver  ADD COLUMN adr_cert_valid_to     DATE;
ALTER TABLE vehicle ADD COLUMN adr_approval_valid_to DATE;
