-- Кабинеты внешних пользователей накладных (MIGRATION.md 1.1 / 3.11 — legacy роли client_sender, client_forwarder,
-- customs_officer): навигация ролей → раздел «Накладные» (consignments). Сами роли и привязка пользователя к клиентам
-- (атрибут clientIds → claim client_ids) — в Keycloak (infra/keycloak/epd-realm.json).
insert into role_access (role, home_key, nav_keys) values
  ('CLIENT_SENDER',    'consignments', 'consignments'),
  ('CLIENT_FORWARDER', 'consignments', 'consignments'),
  ('CUSTOMS_OFFICER',  'consignments', 'consignments')
on conflict (role) do nothing;
