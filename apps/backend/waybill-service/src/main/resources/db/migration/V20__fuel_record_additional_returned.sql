-- Довыдача топлива в пути и возврат неиспользованного остатка на базу («Иловагӣ» /
-- «Баргардонида шуд» граф бланков 1-А/2-Б/3-С/5Б-БМ) — раньше не моделировались вовсе,
-- поэтому WaybillPrintService печатал эти графы пустыми строками (spec/notes/04-гэп-анализ).
ALTER TABLE fuel_record
    ADD COLUMN additional_given NUMERIC(8,2),
    ADD COLUMN returned         NUMERIC(8,2);
