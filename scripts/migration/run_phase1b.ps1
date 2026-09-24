# =============================================================================
# Migration legacy "Rohkhat" MySQL -> e-Waybill PostgreSQL (master-data).
# PHASE 1b (brand fuel norms) + PHASE 3c (routes lost by phases 3/3b)
# + PHASE 3d (route coefficients lost by phases 3/3b, AUDIT.md finding 29).
# Idempotent, repeatable. The file is pure ASCII on purpose (Windows PowerShell 5.1).
#
#   18_staging_phase1b_3c_ddl.sql  - staging tables (DROP + CREATE)
#   19_phase1b_brand_norms.sql     - UPDATE brand: ONLY empty fuel_100 / fuel_100_dushanbe /
#                                    fuel_hour / fuel_interior_heating / number (<= 500 rows)
#   20_phase3c_routes.sql          - INSERT routes missing by name inside the organization
#                                    (duplicate legacy numbers get "<number>/<legacy id>")
#   21_phase3d_route_coefs.sql     - UPDATE route: ONLY empty winter / mountain / in-city
#                                    coefficients, matched by organization + number + name
#
# Pipeline per table (same as run_phase1_2.ps1, protects UTF-8 from PowerShell 5.1):
#   mysql batch (-N -B) inside the container -> TSV in /tmp -> docker cp to host ->
#   docker cp into postgres -> server-side COPY ... WITH (FORMAT text, NULL 'NULL').
#
# Run:  powershell -ExecutionPolicy Bypass -File .\run_phase1b.ps1
#       (-PgContainer <name> runs it against another Postgres container, e.g. a scratch
#        replica of the reference tables for a dry test)
# =============================================================================
param([string]$PgContainer = 'epd-prod-postgres')

$ErrorActionPreference = 'Stop'
$MYSQL = 'rohkhattjralavel-db-1'
$PG    = $PgContainer
$DB    = 'masterdata'
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

# Export queries (MySQL). Organization key of a route = the same key phases 2/2b used:
# companies.rma, or 'MIG'||id for companies without rma (phase 2b).
$queries = [ordered]@{
  'stg_brand_norms' = @'
SELECT id, name, model, number, fuel_100, fuel_100_dushanbe, fuel_hour, fuel_interior_heating FROM brands;
'@
  'stg_routes_3c' = @'
SELECT r.id, r.number, r.type_id, r.name_a, r.name_b, r.distance_a, r.distance_b,
       r.begin_path_a, r.begin_path_b, r.planned_lap, r.coe_use_capacity, r.average_length_pass_seat,
       r.region_id, r.station_coef, r.road_quality, r.transport_type_id,
       CASE WHEN c.id IS NULL THEN NULL ELSE COALESCE(NULLIF(TRIM(c.rma), ''), CONCAT('MIG', c.id)) END,
       r.additional_fuel_100, r.additional_fuel, r.cond_fuel, r.heating_fuel, r.excluding_coef,
       w.name, r.mountain_coef_id, r.in_city_coef_id
FROM routes r
LEFT JOIN companies c ON c.id = r.company_id AND c.deleted_at IS NULL
LEFT JOIN fuel_winter_coef w ON w.id = r.winter_coef_id;
'@
}

$countSql = "SELECT (SELECT count(*) FROM brand) AS brands, (SELECT count(*) FROM brand WHERE COALESCE(btrim(fuel_100),'') NOT IN ('','[]','null')) AS brands_with_norm, (SELECT count(*) FROM route) AS routes, (SELECT count(winter_coef_id) FROM route) AS routes_with_winter, (SELECT count(mountain_coef_value) FROM route) AS routes_with_mountain;"

Write-Host '== BEFORE ==' -ForegroundColor Cyan
docker exec $PG psql -U epd -d $DB -c $countSql

Write-Host '== 1) staging DDL ==' -ForegroundColor Cyan
Invoke-PgFile (Join-Path $SCRIPTDIR '18_staging_phase1b_3c_ddl.sql')

Write-Host '== 2) legacy -> staging ==' -ForegroundColor Cyan
foreach ($stg in $queries.Keys) {
  Write-Host "   $stg"
  Load-Table $stg $queries[$stg]
}

Write-Host '== 3) PHASE 1b: brand fuel norms (fill empty only) ==' -ForegroundColor Cyan
Invoke-PgFile (Join-Path $SCRIPTDIR '19_phase1b_brand_norms.sql')

Write-Host '== 4) PHASE 3c: missing routes (INSERT only) ==' -ForegroundColor Cyan
Invoke-PgFile (Join-Path $SCRIPTDIR '20_phase3c_routes.sql')

Write-Host '== 5) PHASE 3d: route coefficients (fill empty only) ==' -ForegroundColor Cyan
Invoke-PgFile (Join-Path $SCRIPTDIR '21_phase3d_route_coefs.sql')

Write-Host '== AFTER ==' -ForegroundColor Cyan
docker exec $PG psql -U epd -d $DB -c $countSql

Write-Host 'Done. waybill-service caches brands/routes (60 s) and brand norms (5 min) - calculations pick the change up after that.' -ForegroundColor Green
