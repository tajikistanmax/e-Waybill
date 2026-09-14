-- Реестр авторизованных МОБИЛЬНЫХ УСТРОЙСТВ водителей — перенос legacy-справочника «Телефонҳо»
-- (phone_infos) из боевой платформы. Хранит, с какого устройства (бренд/модель) водитель
-- конкретной корхоны авторизовался в мобильном приложении.
--
-- Все ссылки «мягкие» (по РМА, без жёстких FK — как в прочих перенесённых таблицах, ср. route/client
-- .organization_rma в V9): organization_rma — РМА корхоны (обязательно), driver_rma — табель/РМА
-- водителя (может отсутствовать), driver_name — ФИО (денормализовано, как в legacy).
-- authorized_at — дата/время авторизации устройства.
--
-- Этап 1 — только реестр (ручной CRUD, MobileDeviceController). Автозаполнение из мобильного
-- приложения при авторизации — этап 2 (здесь не реализовано).
CREATE TABLE mobile_device (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_rma VARCHAR(32)  NOT NULL,
    driver_rma       VARCHAR(32),
    driver_name      VARCHAR(200) NOT NULL,
    brand            VARCHAR(120),
    model            VARCHAR(120) NOT NULL,
    authorized_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- Мультиарендный листинг (тенант видит только свою корхону) + сортировка по дате авторизации.
CREATE INDEX idx_mobile_device_org ON mobile_device (organization_rma, authorized_at DESC);

-- Демо-записи для наглядности (реестр в бою наполняется при авторизации устройств).
-- Идемпотентно: вставляем только если для этой пары (organization_rma, driver_rma) записи ещё нет.
INSERT INTO mobile_device (organization_rma, driver_rma, driver_name, brand, model, authorized_at)
SELECT * FROM (VALUES
    ('025680800', '111111111', 'Раҳимов Далер Саидович',   'Samsung', 'Galaxy A54', now() - INTERVAL '3 day'),
    ('025680800', '222222222', 'Каримов Фаррух Назарович',  'Xiaomi',  'Redmi Note 12', now() - INTERVAL '10 day'),
    ('100002000', '333333333', 'Шарипов Умед Ҷамшедович',   'Apple',   'iPhone 12', now() - INTERVAL '20 day')
) AS demo(organization_rma, driver_rma, driver_name, brand, model, authorized_at)
WHERE NOT EXISTS (
    SELECT 1 FROM mobile_device md
    WHERE md.organization_rma = demo.organization_rma AND md.driver_rma = demo.driver_rma
);
