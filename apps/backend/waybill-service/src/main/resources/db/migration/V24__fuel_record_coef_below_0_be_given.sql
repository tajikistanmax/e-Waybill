-- Перенос 1-в-1 (MIGRATION.md §5.6): поля топливной строки legacy «Роҳхат», которых не было в модели.
--   coef_below_0 — «Коефитсенти ҳарорати аз 0 поён»: надбавка топлива при температуре ниже 0 °C, л.
--                  В оригинале прибавляется к выданному (helpers.php fuel_calc / fuel_calc_day).
--   be_given     — «Дода шавад»: норма к выдаче, л (хранимое поле; в отчёте типа 10 колонка be_give;
--                  в оригинале префилл из предыдущего ПЛ того же ТС — parking_fuel_give).
-- Ранее WaybillCalcAssembler подавал coef_below_0 и additional в расчёт нулями.
ALTER TABLE fuel_record
    ADD COLUMN coef_below_0 NUMERIC(8,2),
    ADD COLUMN be_given     NUMERIC(8,2);
