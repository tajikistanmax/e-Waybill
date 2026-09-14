-- V44 сделала audit_log безусловно append-only, но это блокирует и собственную
-- одноразовую миграцию AuditHashChainMigration, которой нужно ЗАПОЛНИТЬ (UPDATE)
-- prev_hash/record_hash у исторических записей, созданных до введения цепочки.
-- Уточняем: UPDATE разрешён РОВНО в этом одном случае — заполнение ранее-NULL
-- hash-колонок без изменения ни одного другого поля записи; DELETE запрещён
-- всегда, любой другой UPDATE (включая повторное изменение уже заполненного
-- record_hash — например, попытку скрыть подделку) — запрещён.
CREATE OR REPLACE FUNCTION audit_log_immutable() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'audit_log append-only: DELETE запрещён (ИБ-13.6.3)';
    END IF;

    IF TG_OP = 'UPDATE'
        AND OLD.prev_hash IS NULL AND OLD.record_hash IS NULL
        AND NEW.prev_hash IS NOT NULL AND NEW.record_hash IS NOT NULL
        AND NEW.id = OLD.id AND NEW.seq = OLD.seq AND NEW.occurred_at = OLD.occurred_at
        AND NEW.actor IS NOT DISTINCT FROM OLD.actor
        AND NEW.actor_org IS NOT DISTINCT FROM OLD.actor_org
        AND NEW.action = OLD.action
        AND NEW.entity_type = OLD.entity_type
        AND NEW.entity_key IS NOT DISTINCT FROM OLD.entity_key
        AND NEW.old_value IS NOT DISTINCT FROM OLD.old_value
        AND NEW.new_value IS NOT DISTINCT FROM OLD.new_value
        AND NEW.client_ip IS NOT DISTINCT FROM OLD.client_ip
        AND NEW.user_agent IS NOT DISTINCT FROM OLD.user_agent
    THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'audit_log append-only: UPDATE запрещён, кроме однократного заполнения hash-chain у исторических записей (ИБ-13.6.3)';
END;
$$ LANGUAGE plpgsql;
