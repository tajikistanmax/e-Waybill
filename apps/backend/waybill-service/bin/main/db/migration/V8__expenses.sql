-- Расходы рейса (§12 плана тестирования Test1): суточные, платные дороги, парковка,
-- проживание, ремонт, прочее. Валюта/курс/НДС/чек/подтверждение бухгалтером.
-- Привязка к путевому листу; изменение запрещено после закрытия ПЛ или подтверждения.
create table waybill_expense (
    id             uuid primary key,
    waybill_id     uuid          not null,
    expense_type   varchar(24)   not null,               -- PER_DIEM|TOLL|PARKING|LODGING|REPAIR|OTHER
    amount         numeric(14,2) not null,
    currency       varchar(8)    not null default 'TJS',
    rate           numeric(14,6),                         -- курс к сомони (для валютных расходов)
    vat            numeric(14,2),                         -- сумма НДС
    description    text,
    receipt_number varchar(64),                           -- номер чека/квитанции
    spent_at       date,
    confirmed      boolean       not null default false,  -- подтверждено бухгалтером
    confirmed_by   varchar(128),
    confirmed_at   timestamptz,
    created_by     varchar(128),
    created_at     timestamptz   not null default now()
);
create index ix_expense_waybill on waybill_expense (waybill_id);
