-- Меню ролей по принципу «только своё» (role_access, §29): убираем разделы, не относящиеся к роли.
-- Врач/механик (аутсорс-пункт) работают в своём АРМ (осмотр очереди), а НЕ управляют парком перевозчика.
-- Бухгалтер — финансовая функция; инспектор — контроль. Оперативный дашборд перевозчика им не нужен
-- (у них своя стартовая: отчёты / кабинет инспектора). Зеркалит зашитый дефолт lib/roles.ts.
update role_access set nav_keys = 'med'                              where role = 'DOCTOR';
update role_access set nav_keys = 'tech'                             where role = 'MECHANIC';
update role_access set nav_keys = 'reports,waybills'                 where role = 'ACCOUNTANT';
update role_access set nav_keys = 'inspector,violations,monitoring' where role = 'INSPECTOR';
