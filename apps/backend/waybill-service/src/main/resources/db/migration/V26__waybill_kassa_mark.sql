-- MIGRATION.md 4.7: отметка кассы 3-С «выручка сдана» (legacy waybill3cs.employee_kassa_id, действие pay
-- кассира employee_kassa; колонка списка «Пардохти маблағ»). Кто (РМА сотрудника типа 5 «касса») и когда.
ALTER TABLE waybill ADD COLUMN IF NOT EXISTS kassa_employee_rma VARCHAR(10);
ALTER TABLE waybill ADD COLUMN IF NOT EXISTS kassa_confirmed_at TIMESTAMPTZ;
