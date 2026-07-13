-- Заявка на путевой лист (driver-initiated): водитель из своего кабинета подаёт запрос —
-- госномер ТС (вписывает вручную), тип ПЛ, дата выхода, текущий одометр. Диспетчер его
-- компании проверяет/исправляет и одобряет → создаётся путевой лист (Т1). Водитель НЕ видит
-- списки ТС/водителей фирмы: вписывает госномер, сервер валидирует принадлежность по тенанту;
-- его личность (РМА/ФИО) — из токена входа.
create table waybill_request (
    id                 uuid primary key,
    organization_rma   varchar(10)  not null,
    driver_rma         varchar(10)  not null,
    driver_name        varchar(300),
    vehicle_reg_number varchar(20)  not null,
    waybill_type       varchar(32)  not null,
    requested_from     date,
    odometer           integer,
    communication_type varchar(24),
    route              varchar(500),
    schedule           varchar(200),
    notes              varchar(1000),
    status             varchar(16)  not null default 'PENDING', -- PENDING|APPROVED|REJECTED|CANCELLED
    reject_reason      varchar(500),
    waybill_id         uuid,
    reviewed_by        varchar(128),
    reviewed_at        timestamptz,
    created_at         timestamptz  not null default now(),
    updated_at         timestamptz  not null default now()
);
create index idx_wbreq_org_status on waybill_request (organization_rma, status);
create index idx_wbreq_driver on waybill_request (driver_rma, created_at desc);
