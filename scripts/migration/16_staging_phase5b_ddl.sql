-- ============================================================================
-- ФАЗА 5b — показатели работы архивных путевых листов (дополнение к Ф5).
-- Файл 1/2: staging DDL.  Целевая БД: waybill.  Загружается run_phase5b.ps1 через COPY.
--
-- Ф5 перенесла только «шапку» ПЛ (одометр, даты). Показатели работы в legacy лежат
-- в JSON-тексте и плоских колонках, их разбирает MySQL (JSON_TABLE) при выгрузке:
--   * waybill1ads (1-АД автобус/троллейбус): плоские number_lap, earning, kassa,
--     work_time_minutes, conditioner_time, client_time + JSON `fuels` (строки топлива);
--   * waybill1as / waybill3cs (1-А микроавтобус, 3-С легковой/такси): JSON `work_days`
--     (по дню: date, exit/entry_time, laps, одометр, conditioner/client_time, fuels —
--     вложенная JSON-СТРОКА) + плоская kassa на весь лист;
--   * waybill2bs (2-Б): JSON `work_days[].fuels` (редко); waybill5bbms: JSON `fuels`.
-- Всё text — касты и валидация в файле 17. Staging свой, чужие таблицы не трогаются.
-- ============================================================================

SET client_encoding TO 'UTF8';

-- Рабочие дни (-> work_day). Для 1-АД — одна строка на лист (legacy: один день на ПЛ).
DROP TABLE IF EXISTS stg_wd5b;
CREATE TABLE stg_wd5b (
  src_code          text,   -- 1  '1D'|'1A'|'3C' — как в Ф5 (номер ПЛ = 'MG'||src_code||legacy_id)
  legacy_id         text,   -- 2  legacy PK листа
  seq               text,   -- 3  порядковый номер дня в work_days (1..n); NULL — дня нет, только касса
  work_date         text,   -- 4  дата дня (1-АД: дата created_at листа)
  exit_time         text,   -- 5  время выезда (HH:MM[:SS])
  entry_time        text,   -- 6  время возврата
  odo_exit          text,   -- 7  одометр при выезде (день / шапка 1-АД)
  odo_entry         text,   -- 8  одометр при возврате
  laps              text,   -- 9  рейсы (круги): 1-АД number_lap, 1-А/3-С work_days[].laps
  revenue           text,   -- 10 выручка: 1-АД earning+kassa; 1-А/3-С kassa — на первый день листа
  conditioner_time  text,   -- 11 время работы кондиционера (HH:MM)
  client_time       text,   -- 12 время у клиента (HH:MM)
  work_minutes      text,   -- 13 отработано минут (справочно, в модели e-Waybill не хранится)
  created_at        text    -- 14 created_at листа (справочно)
);

-- Строки топлива (-> fuel_record).
DROP TABLE IF EXISTS stg_fr5b;
CREATE TABLE stg_fr5b (
  src_code            text,   -- 1  '1D'|'1A'|'3C'|'2B'|'5F'
  legacy_id           text,   -- 2  legacy PK листа
  day_seq             text,   -- 3  номер дня work_days (для привязки к work_day), NULL — строка листа
  line_seq            text,   -- 4  порядковый номер строки топлива
  fuel_id             text,   -- 5  вид топлива 1..5 (1 бензин, 2 дизель, 3 СУГ, 4 КПГ, 5 электро)
  fuel_given          text,   -- 6  выдано, л
  additional          text,   -- 7  довыдача в пути, л
  remain_before_exit  text,   -- 8  остаток при выезде, л
  remain_entry        text,   -- 9  остаток при возврате, л
  coef_below_0        text,   -- 10 надбавка при t ниже 0 C, л
  be_given            text,   -- 11 «дода шавад» (норма к выдаче), л
  returned            text    -- 12 возвращено на базу, л
);
