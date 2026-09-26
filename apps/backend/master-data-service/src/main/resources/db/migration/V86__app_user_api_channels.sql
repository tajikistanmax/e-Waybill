-- Сверка 25.09, G2: каналы учётной записи внешней системы-интегратора.
-- В «Роҳхат» у каждой учётки company_for_api свой канал (company.jwt:1 — справочники и путевые
-- листы, company.jwt:2 — только GPS Smart City). Список через запятую: ref, aggregator, gps, neru.
-- NULL — без ограничения: служебная учётная запись межсервисных вызовов (epd-service).
alter table app_user add column api_channels varchar(200);
