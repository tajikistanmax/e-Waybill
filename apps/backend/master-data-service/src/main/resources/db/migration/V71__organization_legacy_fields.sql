-- Поля карточки организации, остававшиеся только в старой платформе (форма company/create,
-- MIGRATION.md Вопрос 8 — закрыт решением владельца 22.09 «перенести все поля»):
--   number    → internal_number  «Рамзи корхона» (внутренний код предприятия);
--   points    → map_points       «Харита» (произвольная отметка на карте: точки/описание);
--   give_fuel → give_fuel        «Сӯзишворӣ» — предприятие само выдаёт топливо.
-- Печать организации (seal_attach) переносится не колонкой, а документом вида SEAL
-- (organization_document) — как остальные вложения карточки.

ALTER TABLE organization ADD COLUMN internal_number VARCHAR(20);
ALTER TABLE organization ADD COLUMN map_points      VARCHAR(500);
ALTER TABLE organization ADD COLUMN give_fuel       BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN organization.internal_number IS 'Рамзи корхона — внутренний код предприятия (legacy companies.number)';
COMMENT ON COLUMN organization.map_points      IS 'Харита — отметка на карте (legacy companies.points)';
COMMENT ON COLUMN organization.give_fuel       IS 'Предприятие выдаёт топливо (legacy companies.give_fuel)';
