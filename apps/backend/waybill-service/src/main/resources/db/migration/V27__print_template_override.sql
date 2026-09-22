-- Редактируемые печатные шаблоны (MIGRATION.md 7.1 / 10.4): переопределение встроенного Thymeleaf-шаблона
-- бланка (templates/print/<name>.html) текстом из БД. Нет строки — печатается встроенный шаблон.
CREATE TABLE print_template_override (
    name        VARCHAR(64)  PRIMARY KEY,          -- имя шаблона без пути и расширения: waybill1ad, blocks, styles, cmr …
    content     TEXT         NOT NULL,             -- полный HTML/Thymeleaf-текст шаблона
    note        VARCHAR(500),                      -- комментарий администратора («что поменяли»)
    updated_by  VARCHAR(150),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE print_template_override IS 'Переопределения печатных шаблонов бланков ПЛ (редактируются в Настройки → Печать), MIGRATION.md 7.1';
