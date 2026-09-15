-- Контрольные количества для BEFORE/AFTER сверки догрузки Ф2b + Ф3b.
-- Отдельно считаем синтетические MIG-организации (rma LIKE 'MIG%').
SELECT 'organization' AS t, count(*) FROM organization
UNION ALL SELECT 'organization(MIGRATED)', count(*) FROM organization WHERE source='MIGRATED'
UNION ALL SELECT 'organization(MIG-synthetic)', count(*) FROM organization WHERE rma LIKE 'MIG%'
UNION ALL SELECT 'vehicle', count(*) FROM vehicle
UNION ALL SELECT 'vehicle(MIGRATED)', count(*) FROM vehicle WHERE source='MIGRATED'
UNION ALL SELECT 'driver', count(*) FROM driver
UNION ALL SELECT 'driver(MIGRATED)', count(*) FROM driver WHERE source='MIGRATED'
UNION ALL SELECT 'employee', count(*) FROM employee
UNION ALL SELECT 'employee(MIGRATED)', count(*) FROM employee WHERE source='MIGRATED'
UNION ALL SELECT 'route', count(*) FROM route
ORDER BY t;
