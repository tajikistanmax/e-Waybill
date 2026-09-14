-- Журнал публичных проверок путевого листа по QR (§18 QA).
--
-- Анонимные сканы через публичный VerifyController (GET /api/v1/verify/{jws}) раньше
-- нигде не фиксировались — надзор не мог увидеть, что конкретный ПЛ проверяли (или
-- пытались проверить недействительный/несуществующий QR). Здесь фиксируется КАЖДОЕ
-- обращение к проверке, включая неуспешные (SIGNATURE_INVALID, NOT_FOUND).
--
-- Приватность (сознательно «минимальный лог»): храним ТОЛЬКО то, что и так присутствует
-- в самом QR/ответе проверки (jti, номер ПЛ, онлайн-статус) плюс IP/User-Agent клиента —
-- по образцу аудита §21 (audit_log.client_ip/user_agent). ПДн водителя/организации в
-- журнал НЕ пишем. Политика ретенции и вопрос о хранении полного IP (а не усечённого) —
-- НЕ реализованы здесь и оставлены на решение (см. отчёт/эксплуатацию).

CREATE TABLE verify_scan_log (
    id             UUID PRIMARY KEY,
    scanned_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- jti из подписанного QR (UUID документа); NULL, если подпись не разобрана
    jti            VARCHAR(64),
    -- Номер ПЛ (claim "num" из токена / waybill.number); NULL для справок и битых QR
    waybill_number VARCHAR(32),
    -- Итог проверки: VALID | SIGNATURE_INVALID | NOT_FOUND
    result         VARCHAR(32)  NOT NULL,
    -- Текущий статус ПЛ на момент проверки (WaybillStatus), если документ найден; иначе NULL
    online_status  VARCHAR(32),
    -- IP/UA клиента — по образцу audit_log (X-Forwarded-For / X-Real-IP / remoteAddr)
    client_ip      VARCHAR(64),
    user_agent     VARCHAR(512)
);

-- История проверок конкретного ПЛ (эндпоинт надзора ?number=...), новые сверху
CREATE INDEX idx_verify_scan_log_number ON verify_scan_log (waybill_number, scanned_at DESC);
-- Поиск по идентификатору документа из QR
CREATE INDEX idx_verify_scan_log_jti ON verify_scan_log (jti, scanned_at DESC);
-- Глобальная лента последних проверок
CREATE INDEX idx_verify_scan_log_scanned ON verify_scan_log (scanned_at DESC);
