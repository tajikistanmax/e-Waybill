#!/bin/sh
# Ежедневное резервное копирование БД платформы (masterdata, waybill) + ротация по сроку хранения.
# Перенос legacy app/Console/Kernel.php: `db:backup` dailyAt('00:00') + `find storage/backups -mtime +10 -delete` weekly
# (MIGRATION.md 11.8). Отличия: ротация ежедневно (не раз в неделю), срок хранения — BACKUP_RETENTION_DAYS (30 по
# умолчанию, ИБ-13.8.5; legacy — 10), дампы шифруются AES-256-CBC (openssl enc -pbkdf2), если задан BACKUP_ENCRYPTION_KEY.
#
# Переменные: PGHOST/PGUSER/PGPASSWORD (libpq), BACKUP_DIR=/backups, BACKUP_TIME=HH:MM (локальное время TZ),
#             BACKUP_RETENTION_DAYS, BACKUP_DATABASES="masterdata waybill", BACKUP_ENCRYPTION_KEY, BACKUP_RUN_NOW=1
#             (сделать копию сразу при старте — для проверки).
# Восстановление: openssl enc -d -aes-256-cbc -pbkdf2 -pass env:BACKUP_ENCRYPTION_KEY -in <db>-<stamp>.dump.enc \
#                   | pg_restore -h postgres -U epd -d <db> --clean --if-exists
set -u
: "${PGHOST:=postgres}"
: "${PGUSER:=epd}"
: "${BACKUP_DIR:=/backups}"
: "${BACKUP_TIME:=00:00}"
: "${BACKUP_RETENTION_DAYS:=30}"
: "${BACKUP_DATABASES:=masterdata waybill}"
: "${BACKUP_ENCRYPTION_KEY:=}"
: "${BACKUP_RUN_NOW:=0}"
export PGHOST PGUSER BACKUP_ENCRYPTION_KEY
mkdir -p "$BACKUP_DIR"

run_backup() {
  stamp=$(date +%Y%m%d-%H%M%S)
  ok=0
  fail=0
  for db in $BACKUP_DATABASES; do
    tmp="$BACKUP_DIR/.$db-$stamp.part"
    if [ -n "$BACKUP_ENCRYPTION_KEY" ]; then
      out="$BACKUP_DIR/$db-$stamp.dump.enc"
      if pg_dump -Fc -d "$db" | openssl enc -aes-256-cbc -pbkdf2 -salt -pass env:BACKUP_ENCRYPTION_KEY -out "$tmp" \
         && [ -s "$tmp" ]; then
        mv "$tmp" "$out"; ok=$((ok + 1)); echo "[OK] $out ($(stat -c %s "$out") bytes, aes-256-cbc)"
      else
        rm -f "$tmp"; fail=$((fail + 1)); echo "[FAIL] $db"
      fi
    else
      out="$BACKUP_DIR/$db-$stamp.dump"
      if pg_dump -Fc -d "$db" -f "$tmp" && [ -s "$tmp" ]; then
        mv "$tmp" "$out"; ok=$((ok + 1)); echo "[OK] $out ($(stat -c %s "$out") bytes, NOT encrypted: BACKUP_ENCRYPTION_KEY is empty)"
      else
        rm -f "$tmp"; fail=$((fail + 1)); echo "[FAIL] $db"
      fi
    fi
  done
  find "$BACKUP_DIR" -maxdepth 1 -type f \( -name '*.dump' -o -name '*.dump.enc' \) -mtime +"$BACKUP_RETENTION_DAYS" -print -delete \
    | sed 's/^/[ROTATE] deleted /'
  echo "=== $(date '+%Y-%m-%dT%H:%M:%S%z') backup done: OK=$ok FAIL=$fail (retention ${BACKUP_RETENTION_DAYS}d) ==="
  [ "$fail" -eq 0 ]
}

# Секунд до ближайшего BACKUP_TIME (HH:MM) по локальному времени контейнера (TZ).
seconds_until() {
  h=${1%%:*}; m=${1##*:}; h=${h#0}; m=${m#0}
  H=$(date +%H); M=$(date +%M); S=$(date +%S); H=${H#0}; M=${M#0}; S=${S#0}
  now=$((H * 3600 + M * 60 + S))
  target=$((h * 3600 + m * 60))
  [ "$target" -le "$now" ] && target=$((target + 86400))
  echo $((target - now))
}

echo "epd backup sidecar: databases=[$BACKUP_DATABASES] host=$PGHOST time=$BACKUP_TIME retention=${BACKUP_RETENTION_DAYS}d encrypted=$([ -n "$BACKUP_ENCRYPTION_KEY" ] && echo yes || echo NO)"
if [ "$BACKUP_RUN_NOW" = "1" ]; then
  run_backup || true
fi
while :; do
  s=$(seconds_until "$BACKUP_TIME")
  echo "next backup in ${s}s (at $BACKUP_TIME $(date +%Z))"
  sleep "$s"
  run_backup || true
  sleep 60
done
