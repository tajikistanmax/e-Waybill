-- Справочник регионов (зон деятельности) РТ — «минтақаҳо».
-- Задел под региональную отчётность/фильтры: раньше регион существовал только как «мягкий»
-- числовой код region_id (1..7) в других таблицах (city.region_id, organization.region_id,
-- route.region_id, coefficient.region_id) без справочной таблицы. Здесь заводим справочник,
-- не трогая существующий region_id (связь «мягкая», по code; жёсткого FK намеренно нет, чтобы
-- не ломать текущие данные).
--
-- Ключ справочника — числовой code (1..7): те же 7 именованных зон, что использует фронт
-- (REGION_OPTIONS в waybills/new) и что заданы в spec/data/dictionaries.yaml (регионы):
-- 1=Душанбе, 2=ВМКБ (ГБАО), 3=Суғд, 4=Рашт, 5=Хатлон-Бохтар, 6=Хатлон-Кӯлоб, 7=Ҳисор.
-- Коды выбраны так, чтобы совпасть с уже используемыми region_id (см. сид городов V54, где
-- city.region_id ∈ 1..7 распределены ровно по этим зонам).
-- Двуязычное наименование: name_ru обязательно, name_tj — тадж. название (приём как в V59/V60).
CREATE TABLE region (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code       SMALLINT     NOT NULL,
    name_ru    VARCHAR(200) NOT NULL,
    name_tj    VARCHAR(200),
    sort_order SMALLINT     NOT NULL DEFAULT 0,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_region_code UNIQUE (code),
    CONSTRAINT region_code_range CHECK (code BETWEEN 1 AND 7)
);

-- Сид 7 регионов РТ. Идемпотентно: ON CONFLICT (code) DO NOTHING.
-- name_tj — точные ярлыки, используемые во фронте; name_ru — русский эквивалент.
INSERT INTO region (code, name_ru, name_tj, sort_order) VALUES
 (1,'Душанбе','Душанбе',1),
 (2,'ГБАО (Горно-Бадахшанская автономная область)','ВМКБ (Вилояти Мухтори Кӯҳистони Бадахшон)',2),
 (3,'Согдийская область','Вилояти Суғд',3),
 (4,'Раштская зона (районы республиканского подчинения)','Минтақаи Рашт',4),
 (5,'Хатлонская область (Бохтарская зона)','Вилояти Хатлон (Бохтар)',5),
 (6,'Хатлонская область (Кулябская зона)','Вилояти Хатлон (Кӯлоб)',6),
 (7,'Гиссарская зона (районы республиканского подчинения)','Минтақаи Ҳисор',7)
ON CONFLICT (code) DO NOTHING;
