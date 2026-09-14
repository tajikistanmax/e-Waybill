-- Уникальность VIN (vincode) транспортного средства — QA §6.
--
-- Проблема: у vehicle.vincode нет ограничения уникальности (V1__init.sql: «vincode VARCHAR(50)»),
-- поэтому один и тот же VIN мог попасть на несколько карточек ТС (ошибка ввода, повторная
-- регистрация с другим госномером, мусорный push единой платформы). Госномер уже уникален
-- (registration_number ... NOT NULL UNIQUE, V1) и канонизируется в коде (trim + верхний регистр);
-- VIN приводим к тому же режиму — симметрично госномеру.
--
-- ЧАСТИЧНЫЙ ФУНКЦИОНАЛЬНЫЙ индекс (а не колоночный UNIQUE):
--   * UPPER(BTRIM(vincode)) — сравниваем в канонической форме (обрезка пробелов + верхний регистр),
--     ровно как код теперь сохраняет VIN (VehicleController.canonicalVin / SyncController). Без этого
--     "abc123" и " ABC123 " считались бы разными и уникальность обошли бы регистром/пробелом.
--   * WHERE vincode IS NOT NULL AND BTRIM(vincode) <> '' — пустой/NULL VIN разрешён многим ТС
--     и НЕ должен конфликтовать сам с собой (у части парка VIN попросту не заполнен).
--
-- ВНИМАНИЕ (риск при применении к боевой БД): если в текущих данных уже есть НЕПУСТЫЕ дубли VIN
-- (в канонической форме), CREATE UNIQUE INDEX упадёт с "could not create unique index" и миграция
-- откатится. Строки данных здесь НЕ удаляются и НЕ мутируются — устранение дублей это отдельная
-- контролируемая операция (правка/очистка VIN на «правильной» карточке ТС) ДО применения V49.
-- Перед применением проверьте дубли запросом:
--
--   SELECT UPPER(BTRIM(vincode)) AS vin_canon,
--          COUNT(*)              AS cnt,
--          ARRAY_AGG(registration_number ORDER BY registration_number) AS plates
--   FROM   vehicle
--   WHERE  vincode IS NOT NULL AND BTRIM(vincode) <> ''
--   GROUP  BY UPPER(BTRIM(vincode))
--   HAVING COUNT(*) > 1
--   ORDER  BY cnt DESC;
--
-- Ниже блок DO лишь ПРЕДУПРЕЖДАЕТ (RAISE NOTICE) о числе дублей в логе миграции — он не падает
-- и ничего не меняет; жёсткую уникальность закладывает сам CREATE UNIQUE INDEX.

DO $$
DECLARE
    dup_groups BIGINT;
BEGIN
    SELECT COUNT(*) INTO dup_groups
    FROM (
        SELECT UPPER(BTRIM(vincode)) AS vin_canon
        FROM   vehicle
        WHERE  vincode IS NOT NULL AND BTRIM(vincode) <> ''
        GROUP  BY UPPER(BTRIM(vincode))
        HAVING COUNT(*) > 1
    ) d;

    IF dup_groups > 0 THEN
        RAISE NOTICE 'V49: обнаружено % групп дублирующихся VIN — уникальный индекс не создастся, устраните дубли (см. SQL в комментарии миграции)', dup_groups;
    ELSE
        RAISE NOTICE 'V49: дублей VIN не обнаружено — создаём уникальный индекс';
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_vehicle_vincode
    ON vehicle (UPPER(BTRIM(vincode)))
    WHERE vincode IS NOT NULL AND BTRIM(vincode) <> '';

COMMENT ON INDEX uq_vehicle_vincode IS
    'Уникальность VIN ТС в канонической форме UPPER(BTRIM(vincode)); пустой/NULL VIN исключён (QA §6)';
