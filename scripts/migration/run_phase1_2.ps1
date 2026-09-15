# =============================================================================
# Оркестратор миграции: legacy «Роҳхат» MySQL -> e-Waybill PostgreSQL.
# ФАЗА 1 (справочники) + ФАЗА 2 (организации). Идемпотентно, повторяемо.
#
# Конвейер по каждой таблице (защита от порчи UTF-8 в PowerShell 5.1):
#   1) mysql batch (-N -B) внутри контейнера пишет TSV в /tmp (редирект в sh -c);
#   2) docker cp mysql -> ХОСТ -> postgres (переносим БАЙТЫ, не строки PS);
#   3) server-side COPY ... WITH (FORMAT text, NULL 'NULL') в staging-таблицу;
#   4) INSERT ... SELECT ... ON CONFLICT DO NOTHING (файлы 02/03).
# mysql batch экранирует \\, \t, \n так же, как COPY TEXT PostgreSQL — совместимо.
# NULL в batch = токен 'NULL' -> опция COPY NULL 'NULL' -> реальный SQL NULL.
#
# Запуск:  powershell -ExecutionPolicy Bypass -File .\run_phase1_2.ps1
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
  docker exec $PG psql -U epd -d masterdata -v ON_ERROR_STOP=0 -f "/tmp/$base"
}
function Invoke-Pg([string]$sql) { docker exec $PG psql -U epd -d masterdata -c $sql }

# Экспорт одной таблицы MySQL -> staging PostgreSQL.
function Load-Table([string]$stg, [string]$query) {
  $tbl = $stg -replace '^stg_',''
  $qfile = Join-Path $HOSTTMP "q_$tbl.sql"
  # ASCII без BOM: mysql не должен подавиться меткой порядка байтов.
  Set-Content -Path $qfile -Value $query -Encoding ascii
  docker cp $qfile "${MYSQL}:/tmp/q_$tbl.sql"
  docker exec $MYSQL sh -c "mysql --default-character-set=utf8mb4 -urohkhat -psecret rohkhat -N -B < /tmp/q_$tbl.sql > /tmp/$tbl.tsv 2>/dev/null"
  docker cp "${MYSQL}:/tmp/$tbl.tsv" "$HOSTTMP\$tbl.tsv"
  docker cp "$HOSTTMP\$tbl.tsv" "${PG}:/tmp/$tbl.tsv"
  docker exec $PG psql -U epd -d masterdata -c "COPY $stg FROM '/tmp/$tbl.tsv' WITH (FORMAT text, NULL 'NULL');"
}

# --- Экспортные запросы (одинарные кавычки PS: backtick вокруг `check` литерален) ---
$queries = [ordered]@{
  'stg_brands'          = 'SELECT type_id, number, name, model, capacity, carrying, cost_services, net_weight, fuel_100, fuel_100_dushanbe, fuel_hour, fuel_interior_heating, tariff_rate FROM brands;'
  'stg_directions'      = 'SELECT title, number, winter_coef_id, mountain_coef_id, in_city_coef_id, `check` FROM directions WHERE deleted_at IS NULL;'
  'stg_cargos'          = 'SELECT name, type, unit, price, class FROM cargos WHERE deleted_at IS NULL;'
  'stg_cities'          = 'SELECT region_id, code, name FROM cities;'
  'stg_regions'         = 'SELECT code, name FROM regions;'
  'stg_route_types'     = 'SELECT number, name FROM route_types;'
  'stg_drive_classes'   = 'SELECT class, coef FROM drive_classes;'
  'stg_fuel_winter_coef'= 'SELECT name, period_from, period_to, coef FROM fuel_winter_coef;'
  'stg_city_coef'       = 'SELECT name, coef FROM city_coef;'
  'stg_mountain_coef'   = 'SELECT name, coef FROM mountain_coef;'
  'stg_used_coef'       = 'SELECT year, km, coef FROM used_coef;'
  'stg_ext_countries'   = 'SELECT id, title FROM external_countries WHERE deleted_at IS NULL;'
  'stg_ext_cities'      = 'SELECT country_id, title FROM external_cities WHERE deleted_at IS NULL;'
  'stg_companies'       = 'SELECT id, rma, kpp, name, region_id, address, phone, email, name_head, bank, license_activity_from, license_activity_to, status_lock, percent_income, cat_1, cat_2, cat_3, license_number, ownership_id, type_company_id, latitude, longitude, registration_certificate, iktibos, aai, plan_pass_volume, plan_pass_traffic FROM companies WHERE deleted_at IS NULL;'
}

Write-Host '== BEFORE ==' -ForegroundColor Cyan
Invoke-PgFile (Join-Path $SCRIPTDIR '00_counts.sql')

Write-Host '== 1) staging DDL ==' -ForegroundColor Cyan
Invoke-PgFile (Join-Path $SCRIPTDIR '01_staging_ddl.sql')

Write-Host '== 2) выгрузка legacy -> staging ==' -ForegroundColor Cyan
foreach ($stg in $queries.Keys) {
  Write-Host "   $stg"
  Load-Table $stg $queries[$stg]
}

Write-Host '== 3) ФАЗА 1 справочники ==' -ForegroundColor Cyan
Invoke-PgFile (Join-Path $SCRIPTDIR '02_phase1_reference.sql')

Write-Host '== 4) ФАЗА 2 организации ==' -ForegroundColor Cyan
Invoke-PgFile (Join-Path $SCRIPTDIR '03_phase2_organization.sql')

Write-Host '== AFTER ==' -ForegroundColor Cyan
Invoke-PgFile (Join-Path $SCRIPTDIR '00_counts.sql')

Write-Host 'Готово.' -ForegroundColor Green
