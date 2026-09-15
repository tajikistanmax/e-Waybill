-- Брендинг платформы (§29): редактируемые администратором название/подзаголовок и изображения
-- (логотип, фон страницы входа). Тексты — публичная категория настроек branding (видны на входе
-- до аутентификации); изображения — таблица branding_asset (bytea), отдаётся публично через
-- GET /api/v1/branding/{key} (нужно странице входа без токена), меняется только SYSTEM_ADMIN.

-- Тексты бренда. brand_name — проприетарное имя (одинаково на всех языках); подзаголовок при
-- пустом значении фолбэчит на перевод brand.sub на фронте.
insert into platform_setting (id, category, setting_key, value_type, setting_value, name_ru, name_tj, sort_order) values
  (gen_random_uuid(), 'branding', 'brand_name',     'STRING', 'е-Роҳхат',                 'Название платформы', 'Номи платформа', 1),
  (gen_random_uuid(), 'branding', 'brand_subtitle', 'STRING', 'Электронный путевой лист', 'Подзаголовок',       'Зерсарлавҳа',    2);

-- Изображения бренда. Ключ — из фиксированного набора (валидируется в BrandingController):
--   logo     — логотип в шапке/сайдбаре и на странице входа;
--   login_bg — фоновое изображение правой панели страницы входа.
-- Отсутствие строки = используется зашитый дефолт (глиф/─login-bg.png) — фолбэк на фронте.
create table branding_asset (
    asset_key    varchar(32) primary key,
    content_type varchar(64)  not null,
    data         bytea        not null,
    updated_by   varchar(128),
    updated_at   timestamptz  not null default now()
);
