# =============================================================================
# Оркестратор ДОГРУЗКИ миграции: legacy «Роҳхат» MySQL -> e-Waybill PostgreSQL.
# ФАЗА 2b (организации без РМА) + ФАЗА 3b (их ТС/водители/сотрудники/маршруты-сироты).
#
# Проблема: 176 companies с пустым rma не мигрированы в Ф2 (organization.rma NOT NULL),
# из-за чего ~19 280 их ТС и ~35 342 водителя остались сиротами (пропущены в Ф3).
# Здесь: заводим орг с СИНТЕТИЧЕСКИМ ключом rma='MIG'||legacy_id и переподтягиваем
# ТОЛЬКО сирот (резолв орг по COALESCE(NULLIF(rma,''),'MIG'||id)). Идемпотентно.
#
# Конвейер по каждой таблице (защита от порчи UTF-8 в PowerShell 5.1) — как в Ф3:
#   1) mysql batch (-N -B, --default-character-set=utf8mb4) пишет TSV в /tmp;
#   2) docker cp mysql -> ХОСТ -> postgres (переносим БАЙТЫ, не строки PS);
#   3) server-side COPY ... WITH (FORMAT text, NULL 'NULL') в staging (client_encoding=UTF8);
#   4) INSERT ... SELECT ... ON CONFLICT DO NOTHING (файлы 11/12).
# НИКАКИХ DELETE/UPDATE/TRUNCATE наших данных.
#
# Запуск:  powershell -ExecutionPolicy Bypass -File .\run_phase2b_3b.ps1
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

# Подзапрос орфан-компаний (без РМА) — общий фильтр сирот.
$ORPHAN = "SELECT id FROM companies WHERE deleted_at IS NULL AND (rma IS NULL OR rma='')"

# --- Экспортные запросы legacy (staging всё text) ---
$queries = [ordered]@{
  # Орфан-компании: полный набор полей Ф2 + id.
  'stg_b_companies' = "SELECT id, rma, kpp, name, region_id, address, phone, email, name_head, bank, license_activity_from, license_activity_to, status_lock, percent_income, cat_1, cat_2, cat_3, license_number, ownership_id, type_company_id, latitude, longitude, registration_certificate, iktibos, aai, plan_pass_volume, plan_pass_traffic FROM companies WHERE deleted_at IS NULL AND (rma IS NULL OR rma='');"
  # Карта brand_id -> name (fallback брэнда ТС).
  'stg_b_brands_map' = 'SELECT id, name FROM brands;'
  # ТС/водители/сотрудники/маршруты ТОЛЬКО орфан-компаний.
  'stg_b_parkings'  = "SELECT id, number, registration_number, brand_id, brand_name, capacity, carrying, number_ydak, brand_ydak, carrying_ydak, weight_ydak, number_ydak_2, brand_ydak_2, carrying_ydak_2, weight_ydak_2, tech_inspection_number, tech_inspection_date_to, certificate_number, expire_checklist_number, expire_checklist_date_to, expire_checklist_itl_number, expire_checklist_itl_date_to, year_manufacture, vincode, company_id, air_conditioner, transport_type_id FROM parkings WHERE deleted_at IS NULL AND company_id IN ($ORPHAN);"
  'stg_b_drivers'   = "SELECT id, full_name, number, category, license, passport, degree, med_cert_number, med_cert_valid_date, rma, power_attorney, visa_valid_date, address, phone, email, company_id, contract_number FROM drivers WHERE deleted_at IS NULL AND company_id IN ($ORPHAN);"
  'stg_b_employees' = "SELECT id, type, number, name, company_id, address, phone, rma FROM employees WHERE deleted_at IS NULL AND company_id IN ($ORPHAN);"
  'stg_b_routes'    = "SELECT id, number, type_id, name_a, name_b, distance_a, distance_b, begin_path_a, begin_path_b, planned_lap, coe_use_capacity, average_length_pass_seat, region_id, station_coef, road_quality, transport_type_id, company_id, additional_fuel_100, additional_fuel, cond_fuel, heating_fuel, excluding_coef FROM routes WHERE company_id IN ($ORPHAN);"
}

Write-Host '== BEFORE (target) ==' -ForegroundColor Cyan
Invoke-PgFile (Join-Path $SCRIPTDIR '13_counts_phase2b_3b.sql')

Write-Host '== 1) staging DDL Ф2b/Ф3b ==' -ForegroundColor Cyan
Invoke-PgFile (Join-Path $SCRIPTDIR '10_staging_phase2b_3b_ddl.sql')

Write-Host '== 2) выгрузка legacy (сироты) -> staging ==' -ForegroundColor Cyan
foreach ($stg in $queries.Keys) {
  Write-Host "   $stg"
  Load-Table $stg $queries[$stg]
}

Write-Host '== 3) ФАЗА 2b организации без РМА (INSERT ... ON CONFLICT DO NOTHING) ==' -ForegroundColor Cyan
Invoke-PgFile (Join-Path $SCRIPTDIR '11_phase2b_organization.sql')

Write-Host '== 4) ФАЗА 3b реестры сирот (INSERT ... ON CONFLICT DO NOTHING) ==' -ForegroundColor Cyan
Invoke-PgFile (Join-Path $SCRIPTDIR '12_phase3b_registries.sql')

Write-Host '== AFTER (target) ==' -ForegroundColor Cyan
Invoke-PgFile (Join-Path $SCRIPTDIR '13_counts_phase2b_3b.sql')

Write-Host 'Готово Ф2b + Ф3b.' -ForegroundColor Green
