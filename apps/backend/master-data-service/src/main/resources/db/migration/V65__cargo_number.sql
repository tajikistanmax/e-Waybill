-- MIGRATION.md 2.25: cargos.number (рамзи бор) — сквозной автономер груза, как в legacy
-- (App\Models\Cargo::creating: number = max(number) + 1); печатается в борхате (прил. 1 и 2, «Рамз»).
-- Выдаёт БД (sequence), существующие грузы нумеруются в порядке id.
CREATE SEQUENCE IF NOT EXISTS cargo_number_seq START WITH 1;

ALTER TABLE cargo ADD COLUMN IF NOT EXISTS number BIGINT;

UPDATE cargo c
   SET number = s.n
  FROM (SELECT id, row_number() OVER (ORDER BY id) AS n FROM cargo WHERE number IS NULL) s
 WHERE c.id = s.id;

SELECT setval('cargo_number_seq', (SELECT COALESCE(MAX(number), 0) FROM cargo) + 1, false);

ALTER TABLE cargo ALTER COLUMN number SET DEFAULT nextval('cargo_number_seq');
ALTER TABLE cargo ALTER COLUMN number SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_cargo_number ON cargo (number);
