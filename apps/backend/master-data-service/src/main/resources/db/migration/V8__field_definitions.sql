-- Конструктор дополнительных (кастомных) полей для типов путевых листов.
-- Администратор Минтранса задаёт доп.поля конкретного типа ПЛ (waybill_type) без
-- изменения кода: ключ поля, двуязычная подпись, тип данных и признак обязательности.

CREATE TABLE field_definition (
    id           UUID PRIMARY KEY,
    waybill_type VARCHAR(30)  NOT NULL,   -- тип путевого листа, к которому относится поле
    field_key    VARCHAR(40)  NOT NULL,   -- машинный ключ поля (уникален в пределах типа)
    label_ru     VARCHAR(200) NOT NULL,
    label_tj     VARCHAR(200),
    data_type    VARCHAR(20)  NOT NULL,   -- STRING | NUMBER | DATE | BOOLEAN | ENUM
    required     BOOLEAN      NOT NULL DEFAULT FALSE,
    options      VARCHAR(500),            -- для ENUM: допустимые значения через запятую
    sort_order   SMALLINT     NOT NULL DEFAULT 0,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    UNIQUE (waybill_type, field_key)
);

CREATE INDEX idx_field_definition_type ON field_definition (waybill_type, sort_order);
