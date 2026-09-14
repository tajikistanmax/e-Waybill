-- Контрольные количества целевых реестров (BEFORE/AFTER сверка Ф3).
SELECT 'vehicle' AS t, count(*) FROM vehicle
UNION ALL SELECT 'vehicle(MIGRATED)', count(*) FROM vehicle WHERE source='MIGRATED'
UNION ALL SELECT 'driver', count(*) FROM driver
UNION ALL SELECT 'driver(MIGRATED)', count(*) FROM driver WHERE source='MIGRATED'
UNION ALL SELECT 'employee', count(*) FROM employee
UNION ALL SELECT 'employee(MIGRATED)', count(*) FROM employee WHERE source='MIGRATED'
UNION ALL SELECT 'route', count(*) FROM route
UNION ALL SELECT 'client', count(*) FROM client
ORDER BY t;
