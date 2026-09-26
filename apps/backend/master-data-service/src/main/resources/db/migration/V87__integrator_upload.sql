-- Сверка 25.09, G4: файлы внешней системы-интегратора, как legacy files/upload.
-- В «Роҳхат» система сначала загружает файл (file + file_type), получает file_name, а затем передаёт
-- его в поле *_attach при регистрации организации, ТС, водителя или сотрудника. Здесь файл ждёт
-- этой регистрации; при ней он становится документом субъекта (organization_document / subject_document).
create table integrator_upload (
    id           uuid primary key,
    file_type    varchar(60)  not null,
    file_name    varchar(255) not null unique,
    content_type varchar(120) not null,
    size_bytes   bigint       not null,
    data         bytea        not null,
    uploaded_by  varchar(150),
    uploaded_at  timestamptz  not null default now(),
    -- Когда файл стал документом субъекта; повторная ссылка на него документ не дублирует.
    used_at      timestamptz
);

create index ix_integrator_upload_uploaded_by on integrator_upload (uploaded_by);
