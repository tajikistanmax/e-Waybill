-- Демо-план грузовых перевозок (тыс. тонн / млн т-км) для сводного отчёта 2-Б / 5Б-БМ.
INSERT INTO waybill_plan (id, organization_rma, region_id, plan_year, plan_kind, volume_thousand, rotation_million, note) VALUES
    (gen_random_uuid(), '100003000', 2, EXTRACT(YEAR FROM now())::int,     'CARGO', 40, 6, 'демо-план ҶДММ Боркашонии Суғд'),
    (gen_random_uuid(), '100003000', 2, EXTRACT(YEAR FROM now())::int - 1, 'CARGO', 36, 5, 'демо-план прошлого года'),
    (gen_random_uuid(), NULL,        NULL, EXTRACT(YEAR FROM now())::int,   'CARGO', 900, 130, 'республиканский план грузов'),
    (gen_random_uuid(), NULL,        NULL, EXTRACT(YEAR FROM now())::int-1, 'CARGO', 850, 120, 'республиканский план грузов прошлого года')
ON CONFLICT DO NOTHING;
