-- Настройки платформы (административная подсистема, §29 ТЗ).
-- Самоописываемые: каждая настройка несёт тип и метку → фронт рендерит форму обобщённо.
-- Значения реально влияют на поведение (контакты поддержки → страница входа/помощь,
-- опции печати → бланк ПЛ, язык по умолчанию → новые сессии). Изменение — SYSTEM_ADMIN,
-- пишется в журнал аудита. Чтение публичных категорий — без токена (для страницы входа).
create table platform_setting (
    id            uuid primary key,
    category      varchar(64)  not null,
    setting_key   varchar(128) not null,
    value_type    varchar(16)  not null default 'STRING',   -- STRING | NUMBER | BOOLEAN | ENUM
    setting_value text,
    options       text,                                      -- для ENUM: варианты через запятую
    name_ru       varchar(256) not null,
    name_tj       varchar(256),
    sort_order    smallint     not null default 0,
    updated_by    varchar(128),
    updated_at    timestamptz  not null default now(),
    constraint uq_platform_setting unique (category, setting_key)
);

-- Категория «general» (контакты/идентификация) — ПУБЛИЧНАЯ (видна на странице входа).
insert into platform_setting (id, category, setting_key, value_type, setting_value, name_ru, name_tj, sort_order) values
  (gen_random_uuid(), 'general', 'support_phone',   'STRING', '+992 44 600 70 07',           'Телефон поддержки',      'Телефони дастгирӣ',        1),
  (gen_random_uuid(), 'general', 'support_email',   'STRING', 'support@dts.tj',              'E-mail поддержки',       'E-mail дастгирӣ',          2),
  (gen_random_uuid(), 'general', 'support_website', 'STRING', 'https://dts.tj',              'Сайт платформы',         'Сомонаи платформа',        3),
  (gen_random_uuid(), 'general', 'support_hours',   'STRING', 'Пн–Пт, 08:00–17:00',          'Часы работы поддержки',  'Соатҳои кори дастгирӣ',    4);

-- Категория «print» — опции печатного бланка ПЛ (учитываются на /waybills/[id]/print).
insert into platform_setting (id, category, setting_key, value_type, setting_value, options, name_ru, name_tj, sort_order) values
  (gen_random_uuid(), 'print', 'show_qr',    'BOOLEAN', 'true',  null,      'Печатать QR-код',            'Чоп кардани QR-код',           1),
  (gen_random_uuid(), 'print', 'show_stamp', 'BOOLEAN', 'true',  null,      'Место для печати и подписи', 'Ҷой барои мӯҳр ва имзо',       2),
  (gen_random_uuid(), 'print', 'paper_size', 'ENUM',    'A4',    'A4,A5',   'Размер бланка',              'Андозаи бланк',                3);

-- Категория «interface» — язык интерфейса по умолчанию для новых сессий.
insert into platform_setting (id, category, setting_key, value_type, setting_value, options, name_ru, name_tj, sort_order) values
  (gen_random_uuid(), 'interface', 'default_language', 'ENUM', 'ru', 'ru,tj', 'Язык по умолчанию', 'Забони пешфарз', 1);
