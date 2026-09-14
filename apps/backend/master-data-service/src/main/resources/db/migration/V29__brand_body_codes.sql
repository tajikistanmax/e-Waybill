-- Правка кодов кузова марок (V27): первая цифра brand.number выбирает расчётную ветку
-- (1/2/4 — бортовой, 3 — самосвал, 5 — спецтехника, 8 — спецтехника в движении).
-- КамАЗ-65115 — самосвал; Volvo FH — тягач (бортовая ветка).

UPDATE brand SET number = '30000' WHERE name = 'КамАЗ' AND number = '60000';
UPDATE brand SET number = '40000' WHERE name = 'Volvo' AND number = '70000';
