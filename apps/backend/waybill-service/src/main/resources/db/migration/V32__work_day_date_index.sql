-- Отчёты относят многодневные 1-А / 3-С к периоду по датам рабочих дней (сверка 25.09, D3):
-- «переходящие» листы ищутся по work_date — без индекса это полный проход по 1,2 млн дней.
CREATE INDEX IF NOT EXISTS ix_work_day_date ON work_day (work_date, waybill_id);
