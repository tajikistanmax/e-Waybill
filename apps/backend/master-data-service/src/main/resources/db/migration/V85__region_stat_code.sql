-- Регионы (сверка 25.09, E6): в «Роҳхат» у минтақа есть «Рамз» — статистический код зоны
-- (regions.code: 3501 Душанбе … 3590 ВМКБ). У нас код региона — порядковый 1..7 (ключ region_id),
-- рамз не переносился. Заводим его отдельной колонкой и заполняем значениями «Роҳхат».
ALTER TABLE region ADD COLUMN stat_code VARCHAR(10);

UPDATE region SET stat_code = CASE code
    WHEN 1 THEN '3501'
    WHEN 2 THEN '3590'
    WHEN 3 THEN '3505'
    WHEN 4 THEN '3509'
    WHEN 5 THEN '3504'
    WHEN 6 THEN '3507'
    WHEN 7 THEN '3508'
END
WHERE stat_code IS NULL;
