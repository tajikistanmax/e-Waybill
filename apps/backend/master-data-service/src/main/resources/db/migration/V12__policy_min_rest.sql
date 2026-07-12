-- Минимальный отдых водителя между рейсами (§5 плана тестирования Test1), в часах.
-- 0 = правило выключено (по умолчанию, чтобы не блокировать существующие процессы);
-- администратор задаёт значение (напр. 8) в /settings/policies. Разрешение — как у прочих
-- правил (VEHICLE_TYPE > ORGANIZATION > NATIONAL). Проверка — в waybill-service при create().
INSERT INTO policy (id, scope_level, scope_key, rule_key, rule_value) VALUES
    ('44444444-0000-0000-0000-000000000021', 'NATIONAL', '', 'min_rest_hours', '0');
