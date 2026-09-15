# =============================================================================
# Оркестратор миграции: legacy «Роҳхат» MySQL -> e-Waybill PostgreSQL.
# ФАЗА 3 — реестры (parkings->vehicle, drivers->driver, employees->employee,
# routes->route). Идемпотентно (INSERT ... ON CONFLICT DO NOTHING), повторяемо.
#
# Конвейер по каждой таблице (защита от порчи UTF-8 в PowerShell 5.1):
#   1) mysql batch (-N -B, --default-character-set=utf8mb4) пишет TSV в /tmp
#      (редирект внутри контейнера через sh -c);
#   2) docker cp mysql -> ХОСТ -> postgres (переносим БАЙТЫ, не строки PS);
#   3) server-side COPY ... WITH (FORMAT text, NULL 'NULL') в staging (client_encoding=UTF8);
#   4) INSERT ... SELECT ... ON CONFLICT DO NOTHING (файл 05).
# НЕ пайпим между контейнерами через PowerShell (портит UTF-8).
#
# Запуск:  powershell -ExecutionPolicy Bypass -File .\run_phase3.ps1
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
  'stg_brands_map'    = 'SELECT id, name FROM brands;'
  'stg_parkings'      = 'SELECT id, number, registration_number, brand_id, brand_name, capacity, carrying, number_ydak, brand_ydak, carrying_ydak, weight_ydak, number_ydak_2, brand_ydak_2, carrying_ydak_2, weight_ydak_2, tech_inspection_number, tech_inspection_date_to, certificate_number, expire_checklist_number, expire_checklist_date_to, expire_checklist_itl_number, expire_checklist_itl_date_to, year_manufacture, vincode, company_id, air_conditioner, transport_type_id FROM parkings WHERE deleted_at IS NULL;'
  'stg_drivers'       = 'SELECT id, full_name, number, category, license, passport, degree, med_cert_number, med_cert_valid_date, rma, power_attorney, visa_valid_date, address, phone, email, company_id, contract_number FROM drivers WHERE deleted_at IS NULL;'
  'stg_employees'     = 'SELECT id, type, number, name, company_id, address, phone, rma FROM employees WHERE deleted_at IS NULL;'
  'stg_routes'        = 'SELECT id, number, type_id, name_a, name_b, distance_a, distance_b, begin_path_a, begin_path_b, planned_lap, coe_use_capacity, average_length_pass_seat, region_id, station_coef, road_quality, transport_type_id, company_id, additional_fuel_100, additional_fuel, cond_fuel, heating_fuel, excluding_coef FROM routes;'
}

Write-Host '== BEFORE (target реестры) ==' -ForegroundColor Cyan
Invoke-PgFile (Join-Path $SCRIPTDIR '06_counts_phase3.sql')

Write-Host '== 1) staging DDL Ф3 ==' -ForegroundColor Cyan
Invoke-PgFile (Join-Path $SCRIPTDIR '04_staging_phase3_ddl.sql')

Write-Host '== 2) выгрузка legacy -> staging ==' -ForegroundColor Cyan
foreach ($stg in $queries.Keys) {
  Write-Host "   $stg"
  Load-Table $stg $queries[$stg]
}

Write-Host '== 3) ФАЗА 3 реестры (INSERT ... ON CONFLICT DO NOTHING) ==' -ForegroundColor Cyan
Invoke-PgFile (Join-Path $SCRIPTDIR '05_phase3_registries.sql')

Write-Host '== AFTER (target реестры) ==' -ForegroundColor Cyan
Invoke-PgFile (Join-Path $SCRIPTDIR '06_counts_phase3.sql')

Write-Host 'Готово Ф3.' -ForegroundColor Green
