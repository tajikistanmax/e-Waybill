# =============================================================================
# Оркестратор миграции: legacy «Роҳхат» MySQL -> e-Waybill PostgreSQL.
# ФАЗА 4 (вариант A, финальная) — реестр мобильных устройств
# (phone_infos -> mobile_device). Идемпотентно, повторяемо.
#
# Конвейер (защита от порчи UTF-8 в PowerShell 5.1) — как в Ф3:
#   1) mysql batch (-N -B, --default-character-set=utf8mb4) пишет TSV в /tmp
#      (редирект внутри контейнера через sh -c);
#   2) docker cp mysql -> ХОСТ -> postgres (переносим БАЙТЫ, не строки PS);
#   3) server-side COPY ... WITH (FORMAT text, NULL 'NULL') в staging (client_encoding=UTF8);
#   4) INSERT ... SELECT ... WHERE NOT EXISTS (файл 08).
# НЕ пайпим между контейнерами через PowerShell (портит UTF-8).
#
# Запуск:  powershell -ExecutionPolicy Bypass -File .\run_phase4.ps1
# =============================================================================

$ErrorActionPreference = 'Stop'
$MYSQL = 'rohkhattjralavel-db-1'
$PG    = 'epd-prod-postgres'
$SCRIPTDIR = $PSScriptRoot
$HOSTTMP = Join-Path $env:TEMP 'ewb_migration'
New-Item -ItemType Directory -Force $HOSTTMP | Out-Null

function Invoke-PgFile([string]$file) {
  $base = Split-Path $file -Leaf
  docker cp $file "${PG}:/tmp/$base"
  docker exec $PG psql -U epd -d masterdata -v ON_ERROR_STOP=1 -f "/tmp/$base"
}
function Invoke-Pg([string]$sql) { docker exec $PG psql -U epd -d masterdata -c $sql }

# Экспорт одной таблицы MySQL -> staging PostgreSQL.
function Load-Table([string]$stg, [string]$query) {
  $tbl = $stg -replace '^stg_',''
  $qfile = Join-Path $HOSTTMP "q_$tbl.sql"
  Set-Content -Path $qfile -Value $query -Encoding ascii   # без BOM: mysql не должен подавиться меткой
  docker cp $qfile "${MYSQL}:/tmp/q_$tbl.sql"
  docker exec $MYSQL sh -c "mysql --default-character-set=utf8mb4 -urohkhat -psecret rohkhat -N -B < /tmp/q_$tbl.sql > /tmp/$tbl.tsv 2>/dev/null"
  docker cp "${MYSQL}:/tmp/$tbl.tsv" "$HOSTTMP\$tbl.tsv"
  docker cp "$HOSTTMP\$tbl.tsv" "${PG}:/tmp/$tbl.tsv"
  docker exec $PG psql -U epd -d masterdata -c "SET client_encoding='UTF8'; COPY $stg FROM '/tmp/$tbl.tsv' WITH (FORMAT text, NULL 'NULL');"
}

# --- Экспортные запросы legacy (staging всё text) ---
$queries = [ordered]@{
  'stg_companies_map' = 'SELECT id, rma FROM companies;'
  'stg_phone_drivers' = 'SELECT id, rma, full_name FROM drivers;'
  'stg_phone_infos'   = 'SELECT id, user_id, type, model, manufacturer, brand, company_id, created_at FROM phone_infos WHERE deleted_at IS NULL;'
}

Write-Host '== BEFORE (target mobile_device) ==' -ForegroundColor Cyan
Invoke-PgFile (Join-Path $SCRIPTDIR '09_counts_phase4.sql')

Write-Host '== 1) staging DDL Ф4 ==' -ForegroundColor Cyan
Invoke-PgFile (Join-Path $SCRIPTDIR '07_staging_phase4_ddl.sql')

Write-Host '== 2) выгрузка legacy -> staging ==' -ForegroundColor Cyan
foreach ($stg in $queries.Keys) {
  Write-Host "   $stg"
  Load-Table $stg $queries[$stg]
}

Write-Host '== 2b) диагностика резолва (сироты по организации) ==' -ForegroundColor Cyan
$diag = @'
SET TimeZone TO 'UTC';
SELECT 'active_loaded'       AS k, count(*) AS n FROM stg_phone_infos
UNION ALL SELECT 'resolvable_org', count(*) FROM stg_phone_infos pi
  JOIN stg_companies_map cm ON cm.id=pi.company_id
  JOIN organization o ON o.rma=btrim(cm.rma) AND btrim(COALESCE(cm.rma,''))<>''
UNION ALL SELECT 'orphan_org', count(*) FROM stg_phone_infos pi
  WHERE NOT EXISTS(SELECT 1 FROM stg_companies_map cm JOIN organization o
        ON o.rma=btrim(cm.rma) AND btrim(COALESCE(cm.rma,''))<>'' WHERE cm.id=pi.company_id)
UNION ALL SELECT 'no_driver_resolved', count(*) FROM stg_phone_infos pi
  JOIN stg_companies_map cm ON cm.id=pi.company_id
  JOIN organization o ON o.rma=btrim(cm.rma) AND btrim(COALESCE(cm.rma,''))<>''
  WHERE NOT EXISTS(SELECT 1 FROM stg_phone_drivers drv WHERE drv.id=pi.user_id AND btrim(COALESCE(drv.rma,''))<>'')
ORDER BY k;
'@
Invoke-Pg $diag

Write-Host '== 3) ФАЗА 4 mobile_device (INSERT ... WHERE NOT EXISTS) ==' -ForegroundColor Cyan
Invoke-PgFile (Join-Path $SCRIPTDIR '08_phase4_mobile_device.sql')

Write-Host '== AFTER (target mobile_device) ==' -ForegroundColor Cyan
Invoke-PgFile (Join-Path $SCRIPTDIR '09_counts_phase4.sql')

Write-Host '== Спот-чек кириллицы (пример driver_name) ==' -ForegroundColor Cyan
Invoke-Pg "SELECT organization_rma, driver_rma, driver_name, brand, model, authorized_at FROM mobile_device WHERE driver_name <> '—' ORDER BY authorized_at DESC LIMIT 5;"

Write-Host 'Готово Ф4.' -ForegroundColor Green
