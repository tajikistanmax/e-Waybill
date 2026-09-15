-- Контрольные количества целевых таблиц (для BEFORE/AFTER сверки).
SELECT 'organization' AS t, count(*) FROM organization
UNION ALL SELECT 'organization(MIGRATED)', count(*) FROM organization WHERE source='MIGRATED'
UNION ALL SELECT 'brand', count(*) FROM brand
UNION ALL SELECT 'direction', count(*) FROM direction
UNION ALL SELECT 'cargo', count(*) FROM cargo
UNION ALL SELECT 'city', count(*) FROM city
UNION ALL SELECT 'external_city', count(*) FROM external_city
UNION ALL SELECT 'region', count(*) FROM region
UNION ALL SELECT 'route_type', count(*) FROM route_type
UNION ALL SELECT 'drive_class', count(*) FROM drive_class
UNION ALL SELECT 'fuel_winter_coef', count(*) FROM fuel_winter_coef
UNION ALL SELECT 'city_coef', count(*) FROM city_coef
UNION ALL SELECT 'mountain_coef', count(*) FROM mountain_coef
UNION ALL SELECT 'used_coef', count(*) FROM used_coef
ORDER BY t;
