#!/bin/sh
# Учение по восстановлению из резервной копии (ИБ-13.8): копия, из которой не восстановились,
# копией не считается. Рабочую базу НЕ трогает:
#   последняя пара копий (masterdata, waybill) из тома backups
#   → временный PostgreSQL в сети стенда
#   → расшифровка ключом BACKUP_ENCRYPTION_KEY и pg_restore (как при настоящем восстановлении)
#   → число строк в основных таблицах: копия рядом с рабочей базой
#   → временный контейнер удаляется (в том числе при ошибке).
# Запуск на сервере:  sh infra/backup/restore-check.sh      (рекомендуется раз в месяц)
# Код выхода 0 — копии расшифровываются и восстанавливаются.
set -eu

NET=${EPD_NETWORK:-epd-rt-prod_default}
BK=${EPD_BACKUP_CONTAINER:-epd-prod-backup}
PG=${EPD_PG_CONTAINER:-epd-prod-postgres}
SCRATCH="epd-restore-check-$$"

latest() {
    docker exec "$BK" sh -c "ls -1t /backups/$1-*.dump /backups/$1-*.dump.enc 2>/dev/null | head -n 1"
}

MD=$(latest masterdata)
WB=$(latest waybill)
if [ -z "$MD" ] || [ -z "$WB" ]; then
    echo "В /backups нет копий обеих баз — сделайте копию: BACKUP_RUN_NOW=1 docker compose ... up -d --force-recreate backup"
    exit 1
fi
echo "Копии: $MD"
echo "       $WB"

cleanup() { docker rm -f "$SCRATCH" >/dev/null 2>&1 || true; }
trap cleanup EXIT INT TERM

docker run -d --name "$SCRATCH" --network "$NET" \
    -e POSTGRES_USER=epd -e POSTGRES_PASSWORD=drill -e POSTGRES_DB=postgres postgres:16-alpine >/dev/null
i=0
until docker exec "$SCRATCH" pg_isready -U epd >/dev/null 2>&1; do
    i=$((i + 1))
    if [ "$i" -gt 60 ]; then echo "Временный PostgreSQL не поднялся"; exit 1; fi
    sleep 2
done
docker exec "$SCRATCH" psql -q -U epd -d postgres -c "create database masterdata" -c "create database waybill"

restore() {
    case "$2" in
        *.enc)
            docker exec -e PGPASSWORD=drill "$BK" sh -c \
                "openssl enc -d -aes-256-cbc -pbkdf2 -pass env:BACKUP_ENCRYPTION_KEY -in '$2' | pg_restore -h $SCRATCH -U epd -d $1 --no-owner --exit-on-error" ;;
        *)
            docker exec -e PGPASSWORD=drill "$BK" pg_restore -h "$SCRATCH" -U epd -d "$1" --no-owner --exit-on-error "$2" ;;
    esac
}
start=$(date +%s)
restore masterdata "$MD"
restore waybill "$WB"
echo "Восстановлено за $(( $(date +%s) - start )) с"

# order by 1: без сортировки строки union all приходят в произвольном порядке, и столбцы
# «копия» и «рабочая база» склеивались бы не по своим таблицам.
Q_MD="select 'организаций', count(*) from organization union all select 'транспорта', count(*) from vehicle union all select 'водителей', count(*) from driver union all select 'учётных записей', count(*) from app_user order by 1"
Q_WB="select 'путевых листов', count(*) from waybill union all select 'рабочих дней', count(*) from work_day union all select 'строк топлива', count(*) from fuel_record order by 1"
echo
echo "таблица|в копии|сейчас в рабочей базе"
{
    docker exec "$SCRATCH" psql -U epd -d masterdata -tA -F'|' -c "$Q_MD"
    docker exec "$SCRATCH" psql -U epd -d waybill -tA -F'|' -c "$Q_WB"
} > /tmp/epd-restore-copy.$$
{
    docker exec "$PG" psql -U epd -d masterdata -tA -F'|' -c "$Q_MD"
    docker exec "$PG" psql -U epd -d waybill -tA -F'|' -c "$Q_WB"
} > /tmp/epd-restore-live.$$
paste -d'|' /tmp/epd-restore-copy.$$ /tmp/epd-restore-live.$$ | awk -F'|' '{print $1 "|" $2 "|" $4}'
rm -f /tmp/epd-restore-copy.$$ /tmp/epd-restore-live.$$
echo
echo "Учение пройдено: копии расшифровываются и восстанавливаются. Разница с рабочей базой — работа после времени копии."
