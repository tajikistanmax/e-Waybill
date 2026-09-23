-- Аутентификация внутри платформы: учётные записи, обновление токенов, ключ подписи.
--
-- Решение владельца 23.09.2026: Keycloak из платформы убрать, пользователей хранить в
-- PostgreSQL. Состав выпускаемого токена остаётся прежним (roles / organization_rma / rma /
-- preferred_username / client_ids), поэтому проверка прав в обеих службах не меняется —
-- меняется только источник токена и место хранения учётных записей.

create table app_user (
    id                    uuid primary key,
    username              varchar(150) not null,
    password_hash         varchar(200) not null,
    first_name            varchar(150),
    last_name             varchar(150),
    email                 varchar(200),
    enabled               boolean      not null default true,
    -- Временный пароль: до смены вход в платформу не даётся (аналог обязательного действия).
    must_change_password  boolean      not null default false,
    -- ИНН сотрудника (подпись титулов) и организации (мультиарендность).
    rma                   varchar(10),
    organization_rma      varchar(10),
    -- Роли и контрагенты кабинета накладных — списком через запятую, как в токене.
    roles                 varchar(500) not null default '',
    client_ids            varchar(1000),
    -- Второй фактор (шаг 5 плана): секрет и признак обязательности.
    totp_secret           varchar(64),
    totp_required         boolean      not null default false,
    -- Защита от подбора пароля.
    failed_attempts       integer      not null default 0,
    locked_until          timestamptz,
    last_login_at         timestamptz,
    created_at            timestamptz  not null default now(),
    updated_at            timestamptz  not null default now()
);

-- Логин уникален независимо от регистра: «Admin» и «admin» — одна учётная запись.
create unique index uq_app_user_username on app_user (lower(username));
create index idx_app_user_organization on app_user (organization_rma);

-- Токены обновления: хранится только отпечаток, сам токен в базе не лежит.
create table auth_refresh_token (
    id          uuid primary key,
    user_id     uuid        not null references app_user (id) on delete cascade,
    token_hash  varchar(100) not null,
    expires_at  timestamptz not null,
    revoked     boolean     not null default false,
    created_at  timestamptz not null default now()
);

create unique index uq_auth_refresh_token_hash on auth_refresh_token (token_hash);
create index idx_auth_refresh_token_user on auth_refresh_token (user_id);
create index idx_auth_refresh_token_expires on auth_refresh_token (expires_at);

-- Ключ подписи токенов. В базе, а не в памяти: иначе перезапуск службы обнуляет все
-- выданные токены и всех выкидывает из системы.
create table auth_signing_key (
    id           varchar(64) primary key,
    private_key  text        not null,
    public_key   text        not null,
    active       boolean     not null default true,
    created_at   timestamptz not null default now()
);
