-- Целостность журнала аудита (ИБ-13.6.3): монотонная последовательность seq для
-- однозначного порядка цепочки (occurred_at может совпасть при конкурентных записях),
-- plus hash-chain (prev_hash/record_hash, SHA-256) — вычисляется приложением
-- (AuditService), проверяется эндпоинтом GET /api/v1/audit/verify.
ALTER TABLE audit_log ADD COLUMN seq BIGSERIAL;
ALTER TABLE audit_log ADD COLUMN prev_hash VARCHAR(64);
ALTER TABLE audit_log ADD COLUMN record_hash VARCHAR(64);
CREATE UNIQUE INDEX idx_audit_log_seq ON audit_log(seq);

-- Неизменяемость на уровне СУБД: append-only не только по соглашению кода, но и
-- физически — UPDATE/DELETE запрещены триггером для ЛЮБОЙ роли, включая владельца
-- таблицы (обычный REVOKE тут бесполезен: владелец обходит ACL). Требует явного
-- DROP TRIGGER, чтобы обойти — это DDL, само по себе заметное и нетихое действие.
CREATE OR REPLACE FUNCTION audit_log_immutable() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_log append-only: UPDATE/DELETE запрещены (ИБ-13.6.3)';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_log_no_update
    BEFORE UPDATE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_immutable();

CREATE TRIGGER trg_audit_log_no_delete
    BEFORE DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_immutable();
