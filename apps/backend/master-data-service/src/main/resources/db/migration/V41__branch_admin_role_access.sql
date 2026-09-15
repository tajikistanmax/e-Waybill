-- Кабинеты администратора компании и филиала после появления иерархии «компания → филиал».
--
-- COMPANY_ADMIN: +access (выдача сотрудникам логинов и ролей модуля),
--                +registry / +dictionaries — теперь на ПРОСМОТР (записи закрыты @PreAuthorize:
--                профиль организации и нац. справочники правит только SYSTEM_ADMIN).
-- BRANCH_ADMIN:  то же, но область — только свой филиал (ограничение по токену на бэкенде);
--                без registry/dictionaries (нужны реже, у филиала своя узкая задача).

update role_access
set nav_keys = 'dashboard,waybills,company,fleet,access,monitoring,violations,reports,registry,dictionaries'
where role = 'COMPANY_ADMIN';

insert into role_access (role, home_key, nav_keys) values
  ('BRANCH_ADMIN', 'dashboard', 'dashboard,waybills,company,fleet,access,monitoring,violations,reports')
on conflict (role) do update set nav_keys = excluded.nav_keys, home_key = excluded.home_key;
