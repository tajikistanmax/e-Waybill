# =============================================================================
# Migration legacy "Rohkhat" MySQL -> e-Waybill PostgreSQL (waybill DB).
# PASSENGER CERTIFICATES (malumotnoma): route tariff dictionary (routemalumotnomas)
# and the certificate archive (malumotnomas + rmalumotnomas), parity audit 25.09 E5/B7.
# Idempotent, repeatable. The file is pure ASCII on purpose (Windows PowerShell 5.1).
#
#   22_staging_malumotnoma_ddl.sql - staging tables (DROP + CREATE)
#   23_malumotnoma_import.sql      - INSERT missing routes (by legacy id) and certificates
#                                    (number = legacy id), lines, sequence bump
#
# Run BEFORE cashiers start issuing certificates in e-Waybill: legacy numbers already
# taken by e-Waybill certificates stop the import (see infra/DEPLOY.md).
#
# Pipeline per table (same as run_phase1b.ps1, protects UTF-8 from PowerShell 5.1):
#   mysql batch (-N -B) inside the container -> TSV in /tmp -> docker cp to host ->
#   docker cp into postgres -> server-side COPY ... WITH (FORMAT text, NULL 'NULL').
#
# Run:  powershell -ExecutionPolicy Bypass -File .\run_malumotnoma.ps1
# =============================================================================
param([string]$PgContainer = 'epd-prod-postgres', [string]$MysqlContainer = 'rohkhattjralavel-db-1')

$ErrorActionPreference = 'Stop'
$MYSQL = $MysqlContainer
$PG    = $PgContainer
$DB    = 'waybill'
$SCRIPTDIR = $PSScriptRoot
$HOSTTMP = Join-Path $env:TEMP 'ewb_migration'
New-Item -ItemType Directory -Force $HOSTTMP | Out-Null

function Invoke-PgFile([string]$file) {
  $base = Split-Path $file -Leaf
  docker cp $file "${PG}:/tmp/$base"
  docker exec $PG psql -U epd -d $DB -v ON_ERROR_STOP=1 -f "/tmp/$base"
  if ($LASTEXITCODE -ne 0) { throw "psql failed on $base" }
}

function Load-Table([string]$stg, [string]$query) {
  $tbl = $stg -replace '^stg_',''
  $qfile = Join-Path $HOSTTMP "q_$tbl.sql"
  Set-Content -Path $qfile -Value $query -Encoding ascii
  docker cp $qfile "${MYSQL}:/tmp/q_$tbl.sql"
  docker exec $MYSQL sh -c "mysql --default-character-set=utf8mb4 -urohkhat -psecret rohkhat -N -B < /tmp/q_$tbl.sql > /tmp/$tbl.tsv 2>/dev/null"
  docker cp "${MYSQL}:/tmp/$tbl.tsv" "$HOSTTMP\$tbl.tsv"
  docker cp "$HOSTTMP\$tbl.tsv" "${PG}:/tmp/$tbl.tsv"
  docker exec $PG psql -U epd -d $DB -v ON_ERROR_STOP=1 -c "COPY $stg FROM '/tmp/$tbl.tsv' WITH (FORMAT text, NULL 'NULL');"
  if ($LASTEXITCODE -ne 0) { throw "COPY failed for $stg" }
}

# Export queries (MySQL). Cashier names come from users (create_user_id / update_user_id).
$queries = [ordered]@{
  'stg_mlm_route' = @'
SELECT id, name, distance, car_price, mbus_price, bus_price FROM routemalumotnomas;
'@
  'stg_mlm' = @'
SELECT m.id, m.fio, cu.name, uu.name, m.created_at, m.updated_at, m.deleted_at, m.age, m.transport_type_id
FROM malumotnomas m
LEFT JOIN users cu ON cu.id = m.create_user_id
LEFT JOIN users uu ON uu.id = m.update_user_id;
'@
  'stg_mlm_line' = @'
SELECT id, route_id, round_trip, malumotnoma_id FROM rmalumotnomas;
'@
}

$countSql = "SELECT (SELECT count(*) FROM malumotnoma_route) AS routes, (SELECT count(*) FROM malumotnoma_route WHERE legacy_id IS NOT NULL) AS legacy_routes, (SELECT count(*) FROM malumotnoma) AS certificates, (SELECT count(*) FROM malumotnoma WHERE legacy) AS legacy_certificates, (SELECT count(*) FROM malumotnoma WHERE annulled_at IS NOT NULL) AS annulled, (SELECT count(*) FROM malumotnoma_line) AS lines, (SELECT last_value FROM malumotnoma_number_seq) AS number_seq;"

Write-Host '== BEFORE ==' -ForegroundColor Cyan
docker exec $PG psql -U epd -d $DB -c $countSql

Write-Host '== 1) staging DDL ==' -ForegroundColor Cyan
Invoke-PgFile (Join-Path $SCRIPTDIR '22_staging_malumotnoma_ddl.sql')

Write-Host '== 2) legacy -> staging ==' -ForegroundColor Cyan
foreach ($stg in $queries.Keys) {
  Write-Host "   $stg"
  Load-Table $stg $queries[$stg]
}

Write-Host '== 3) routes + certificates (INSERT only) ==' -ForegroundColor Cyan
Invoke-PgFile (Join-Path $SCRIPTDIR '23_malumotnoma_import.sql')

Write-Host '== AFTER ==' -ForegroundColor Cyan
docker exec $PG psql -U epd -d $DB -c $countSql

Write-Host 'Done.' -ForegroundColor Green
