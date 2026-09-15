-- Марки демо-парка (совпадают по названию с vehicle.brand существующих ТС стенда),
-- чтобы BrandNormsProvider.forName() находил нормативы расхода для расчёта.
-- Коды кузова (первая цифра number): 1/2/4 бортовой, 3 самосвал, 5 спец, 8 спец-движение.

INSERT INTO brand (id, type_id, number, name, model, capacity, carrying, cost_services,
                   fuel_100, fuel_100_dushanbe, fuel_hour, fuel_interior_heating) VALUES
    (11, 7, '40000', 'Mercedes-Benz Actros',  'Actros 1841', NULL, 20.0, NULL,
        '[{"fuel_id":2,"consumption":29,"ton_for_100":1.3}]', NULL, NULL, NULL),
    (12, 3, '00000', 'Mercedes-Benz Sprinter','Sprinter 515', 19,  NULL, NULL,
        '[{"fuel_id":2,"consumption":13.5}]', '[{"fuel_id":2,"consumption":12.8}]', NULL, NULL),
    (13, 4, '40000', 'Opel Astra',            'Astra J',      4,   NULL, NULL,
        '[{"fuel_id":1,"consumption":8.5}]', '[{"fuel_id":1,"consumption":8.1}]', NULL, NULL),
    (14, 5, '30000', 'КамАЗ-5320',            '5320',         NULL, 8.0,  NULL,
        '[{"fuel_id":2,"consumption":25,"ton_for_100":1.3,"s_for_rais":0.25}]', NULL, NULL, NULL),
    (15, 1, '00000', 'Акиа',                  'Granbird',     45,  NULL, NULL,
        '[{"fuel_id":2,"consumption":34}]', '[{"fuel_id":2,"consumption":31}]', NULL, 2.5);
SELECT setval(pg_get_serial_sequence('brand', 'id'), (SELECT max(id) FROM brand));
