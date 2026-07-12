-- §21: расширение следа аудита сведениями об источнике запроса.
-- client_ip — IP клиента (из X-Forwarded-For за обратным прокси госЦОД, иначе remoteAddr);
-- user_agent — устройство/браузер, инициировавший изменение.
-- Оба поля необязательны: внутренние межсервисные вызовы могут не иметь HTTP-контекста.
ALTER TABLE audit_log ADD COLUMN client_ip  VARCHAR(64);
ALTER TABLE audit_log ADD COLUMN user_agent VARCHAR(512);
