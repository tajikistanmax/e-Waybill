-- MIGRATION.md 2.24: клиент (мизоҷ) — вид клиента и банковские реквизиты, как в legacy clients
-- (type: 1 мизоҷ, 2 борқабулкунанда/грузополучатель, 3 борфиристонанда/грузоотправитель, 4 экспедитор;
-- riam, rma, account, correspondence_account, mfo, bank_name — миграция 2022_07_21_165937).
ALTER TABLE client ADD COLUMN IF NOT EXISTS type                  SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE client ADD COLUMN IF NOT EXISTS riam                  VARCHAR(50);
ALTER TABLE client ADD COLUMN IF NOT EXISTS rma                   VARCHAR(20);
ALTER TABLE client ADD COLUMN IF NOT EXISTS account               VARCHAR(50);
ALTER TABLE client ADD COLUMN IF NOT EXISTS correspondence_account VARCHAR(50);
ALTER TABLE client ADD COLUMN IF NOT EXISTS mfo                   VARCHAR(20);
ALTER TABLE client ADD COLUMN IF NOT EXISTS bank_name             VARCHAR(200);
