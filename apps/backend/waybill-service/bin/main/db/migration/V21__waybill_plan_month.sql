-- Пункт 3 аудита сводных отчётов Минтранса: план не только на год, но и на конкретный
-- месяц (legacy-семантика Y-m). plan_month IS NULL сохраняет прежний смысл «план на весь
-- год» — обратная совместимость с уже существующими строками не нарушена.
ALTER TABLE waybill_plan ADD COLUMN plan_month SMALLINT;
ALTER TABLE waybill_plan ADD CONSTRAINT ck_waybill_plan_month
    CHECK (plan_month IS NULL OR plan_month BETWEEN 1 AND 12);

-- Уникальность области плана расширяется месяцем: у организации/года/вида теперь может
-- быть одна годовая строка (plan_month IS NULL) и до 12 месячных строк одновременно.
DROP INDEX IF EXISTS ux_waybill_plan_scope;
CREATE UNIQUE INDEX ux_waybill_plan_scope
    ON waybill_plan (COALESCE(organization_rma, '*'), plan_year, plan_kind, COALESCE(plan_month, 0));
