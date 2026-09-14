-- Доступ ролей к разделам (§29): какие пункты бокового меню видит роль и её стартовая страница.
-- Это UI-кастомизация НАВИГАЦИИ, НЕ граница безопасности — реальные права проверяет @PreAuthorize
-- по ролям Keycloak в контроллерах (их править нельзя из UI). Позволяет администратору настроить
-- состав кабинетов ролей. Фронт читает конфиг; при недоступности — зашитый дефолт (lib/roles.ts).
-- home_key — стартовый раздел; nav_keys — CSV разделов меню. Значения зеркалят прежний хардкод.
create table role_access (
    role       varchar(48) primary key,
    home_key   varchar(32)  not null,
    nav_keys   text         not null,
    updated_by varchar(128),
    updated_at timestamptz  not null default now()
);

insert into role_access (role, home_key, nav_keys) values
  ('SYSTEM_ADMIN',     'dashboard',  'dashboard,waybills,company,monitoring,registry,violations,reports,dictionaries,settings'),
  ('COMPANY_ADMIN',    'dashboard',  'dashboard,waybills,company,fleet,monitoring,registry,violations,reports,dictionaries,settings'),
  ('DISPATCHER',       'dispatcher', 'dispatcher,dashboard,waybills,fleet,monitoring'),
  ('DOCTOR',           'med',        'med,fleet'),
  ('MECHANIC',         'tech',       'tech,fleet'),
  ('DRIVER',           'driver',     'driver'),
  ('ACCOUNTANT',       'reports',    'dashboard,reports,waybills'),
  ('INSPECTOR',        'inspector',  'inspector,dashboard,violations,monitoring'),
  ('MINTRANS_ANALYST', 'dashboard',  'dashboard,reports,registry,violations,monitoring');
