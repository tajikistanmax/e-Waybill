-- Контрольное количество целевого реестра mobile_device (BEFORE/AFTER сверка Ф4).
-- В таблице нет колонки source, поэтому считаем всего строк (демо-записи + мигрированные).
SELECT 'mobile_device_total' AS t, count(*) AS n FROM mobile_device;
