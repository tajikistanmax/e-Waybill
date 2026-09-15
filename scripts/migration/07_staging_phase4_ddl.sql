-- ============================================================================
-- Миграция боевых данных legacy «Роҳхат» (MySQL) -> e-Waybill (PostgreSQL)
-- ФАЗА 4 (вариант A, финальная) — реестр мобильных устройств
-- (phone_infos -> mobile_device). Файл 1/2: DDL промежуточных (staging) таблиц.
--
-- Все staging-колонки — text: данные приходят из mysql batch-режима как TSV,
-- реальные NULL приходят токеном 'NULL' и превращаются в SQL NULL опцией
-- COPY ... WITH (FORMAT text, NULL 'NULL') на этапе загрузки (см. orchestrator).
-- Скрипт идемпотентен: DROP + CREATE, повторный прогон безопасен.
-- Наши боевые таблицы (в т.ч. mobile_device) этот файл НЕ трогает.
-- ============================================================================

-- Карта company_id -> rma (все компании, включая soft-deleted: сам факт наличия
-- организации в target-таблице organization решает, сирота запись или нет).
DROP TABLE IF EXISTS stg_companies_map;
CREATE TABLE stg_companies_map(id text, rma text);

-- Карта driver.id -> (rma, full_name). phone_infos.user_id ссылается на drivers.id
-- (проверено: 32538/32539 активных строк резолвятся в drivers.id, в users.id — 0).
-- Берём ВСЕХ водителей (в т.ч. soft-deleted): телефон закреплён за человеком,
-- даже если карточка водителя позднее удалена — ФИО/rma нужны для резолва.
DROP TABLE IF EXISTS stg_phone_drivers;
CREATE TABLE stg_phone_drivers(id text, rma text, full_name text);

-- phone_infos -> mobile_device (только deleted_at IS NULL — soft-deleted не грузим).
DROP TABLE IF EXISTS stg_phone_infos;
CREATE TABLE stg_phone_infos(
  id text, user_id text, type text, model text, manufacturer text, brand text,
  company_id text, created_at text
);
