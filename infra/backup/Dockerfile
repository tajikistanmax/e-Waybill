# Сайдкар резервного копирования БД (MIGRATION.md 11.8): pg_dump из образа postgres + openssl для шифрования
# + tzdata для локального времени запуска (BACKUP_TIME в TZ). В postgres:16-alpine openssl-утилиты нет.
FROM postgres:16-alpine
RUN apk add --no-cache openssl tzdata
COPY backup-loop.sh /usr/local/bin/backup-loop.sh
RUN sed -i 's/\r$//' /usr/local/bin/backup-loop.sh && chmod +x /usr/local/bin/backup-loop.sh
ENTRYPOINT ["/usr/local/bin/backup-loop.sh"]
