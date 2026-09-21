-- Индексы под выборки ПО ПЕРИОДУ (отчёты, реестр): после миграции Ф5 в waybill ~2,3 млн строк,
-- а все периодные запросы (created_at between, order by created_at, фильтр организации/статуса)
-- шли полным сканом таблицы. Создание на 2,3 млн строк — десятки секунд на старте сервиса
-- (Flyway), на это время запись в waybill блокируется (обычный CREATE INDEX). Однократно.
CREATE INDEX IF NOT EXISTS idx_waybill_created_at ON waybill (created_at);
CREATE INDEX IF NOT EXISTS idx_waybill_org_created_at ON waybill (organization_rma, created_at);
CREATE INDEX IF NOT EXISTS idx_waybill_status_created_at ON waybill (status, created_at);
