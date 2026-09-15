-- Журнальный номер путевого листа в пределах организации за календарный год.
--
-- Национальный номер (waybill.number, RR-YY-TT-NNNNNNN-K) остаётся глобально
-- уникальным — это идентификатор в национальном реестре. branch_serial — «наш
-- N-й путевой лист в этом году», аналог номера в бумажной книге учёта филиала
-- (у каждого филиала своя сквозная нумерация, сбрасывается 1 января).
-- Присваивается вместе с национальным номером при переходе в READY.

ALTER TABLE waybill ADD COLUMN branch_serial INTEGER;
ALTER TABLE waybill ADD COLUMN branch_serial_year SMALLINT;

CREATE TABLE waybill_org_counter (
    organization_rma VARCHAR(10) NOT NULL,
    counter_year     SMALLINT    NOT NULL,
    counter          INTEGER     NOT NULL DEFAULT 0,
    PRIMARY KEY (organization_rma, counter_year)
);

COMMENT ON TABLE waybill_org_counter IS
    'Счётчик журнальных номеров ПЛ по (организация, год); инкремент под блокировкой строки';
