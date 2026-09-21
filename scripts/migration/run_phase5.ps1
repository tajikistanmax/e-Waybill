# =============================================================================
# Оркестратор миграции: legacy «Роҳхат» MySQL -> e-Waybill PostgreSQL.
# ФАЗА 5 — исторические путевые листы (архив, read-only, вариант B «окном»).
# Цель: БД waybill, таблица waybill (status=ARCHIVED, source=MIGRATED).
#
# Конвейер (как в Ф3, защита UTF-8 в PowerShell 5.1):
#   1) mysql batch (-N -B, utf8mb4) с РЕЗОЛВОМ натуральных ключей (JOIN в MySQL) -> TSV;
#   2) docker cp mysql -> ХОСТ -> postgres (байты, не строки PS);
#   3) server-side COPY -> stg_wb5 (унифицированная шапка под все типы);
#   4) INSERT ... SELECT ... ON CONFLICT (number) DO NOTHING (файл 15).
#
# Окно задаётся -Window (по умолчанию пилотное 2026-08-01). Всё идемпотентно/повторяемо.
# Запуск:  powershell -ExecutionPolicy Bypass -File .\run_phase5.ps1 [-Window 2025-09-21]
# =============================================================================
param([string]$Window = '2026-08-01')

$ErrorActionPreference = 'Stop'
$env:PATH += ';C:\Program Files\Docker\Docker\resources\bin'
$MYSQL = 'rohkhattjralavel-db-1'
$PG    = 'epd-prod-postgres'
$DB    = 'waybill'
$SCRIPTDIR = $PSScriptRoot
$HOSTTMP = Join-Path $env:TEMP 'ewb_migration'
New-Item -ItemType Directory -Force $HOSTTMP | Out-Null

function Invoke-PgFile([string]$file) {
  $base = Split-Path $file -Leaf
  docker cp $file "${PG}:/tmp/$base"
  docker exec $PG psql -U epd -d $DB -v ON_ERROR_STOP=1 -f "/tmp/$base"
}
function Invoke-Pg([string]$sql) { docker exec $PG psql -U epd -d $DB -tA -c $sql }

# Экспорт одной legacy-таблицы (уже унифицированная 22-колоночная шапка) -> stg_wb5.
function Load-Table([string]$name, [string]$query) {
  $qfile = Join-Path $HOSTTMP "q_$name.sql"
  Set-Content -Path $qfile -Value $query -Encoding ascii
  docker cp $qfile "${MYSQL}:/tmp/q_$name.sql"
  # tr -d '\015\000': убрать «голый» CR и NUL из свободного текста legacy — иначе
  # COPY падает ("literal carriage return found in data"). LF-разделители строк целы.
  docker exec $MYSQL sh -c "mysql --default-character-set=utf8mb4 -urohkhat -psecret rohkhat -N -B < /tmp/q_$name.sql 2>/dev/null | tr -d '\015\000' > /tmp/$name.tsv"
  docker cp "${MYSQL}:/tmp/$name.tsv" "$HOSTTMP\$name.tsv"
  docker cp "$HOSTTMP\$name.tsv" "${PG}:/tmp/$name.tsv"
  docker exec $PG psql -U epd -d $DB -c "SET client_encoding='UTF8'; COPY stg_wb5 FROM '/tmp/$name.tsv' WITH (FORMAT text, NULL 'NULL');"
}

# --- Экспортные запросы legacy (натуральные ключи резолвятся здесь же, в MySQL) ---
# Общий «хвост» резолва водителя через pivot parking_driver (~1.01 водителя на ТС):
$PD = "LEFT JOIN (SELECT parking_id, MAX(driver_id) driver_id FROM parking_driver GROUP BY parking_id) pdx ON pdx.parking_id=w.parking_id JOIN drivers d ON d.id=pdx.driver_id AND TRIM(COALESCE(d.rma,''))<>'' AND CHAR_LENGTH(TRIM(d.rma))<=10"

# 22-колоночная шапка (порядок = stg_wb5):
# id, src_code, waybill_type, communication, org_rma, org_name, veh_reg, veh_brand,
# driver_rma, driver_name, 2nd_driver_rma, 2nd_driver_name, route_text, number,
# exit_date, entry_date, odo_exit, odo_entry, schedule, special_mark, created_at, type_service

# waybill3cs -> WB_TAXI (type_service=1) / WB_CAR (2,3).
$q_3cs = @"
SELECT w.id, '3C', CASE WHEN w.type_service=1 THEN 'WB_TAXI' ELSE 'WB_CAR' END, NULL,
  c.rma, c.name, p.registration_number, COALESCE(NULLIF(TRIM(p.brand_name),''), b.name),
  d.rma, d.full_name, NULL, NULL,
  NULLIF(TRIM(CONCAT_WS(' - ', NULLIF(TRIM(r.name_a),''), NULLIF(TRIM(r.name_b),''))),''),
  w.number, w.exit_date, w.entry_date, w.indication_counter_exit, w.indication_counter_entry,
  w.schedule, w.special_mark, w.created_at, w.type_service
FROM waybill3cs w
JOIN companies c ON c.id=w.company_id AND TRIM(COALESCE(c.rma,''))<>''
JOIN parkings p ON p.id=w.parking_id AND TRIM(COALESCE(p.registration_number,''))<>''
LEFT JOIN brands b ON b.id=p.brand_id
LEFT JOIN routes r ON r.id=w.route_id
$PD
WHERE w.deleted_at IS NULL AND w.created_at >= '$Window';
"@

# waybill1as -> WB_MINIBUS.
$q_1as = @"
SELECT w.id, '1A', 'WB_MINIBUS', NULL,
  c.rma, c.name, p.registration_number, COALESCE(NULLIF(TRIM(p.brand_name),''), b.name),
  d.rma, d.full_name, NULL, NULL,
  NULLIF(TRIM(CONCAT_WS(' - ', NULLIF(TRIM(r.name_a),''), NULLIF(TRIM(r.name_b),''))),''),
  w.number, w.exit_date, w.entry_date, w.indication_counter_exit, w.indication_counter_entry,
  w.schedule, w.special_mark, w.created_at, NULL
FROM waybill1as w
JOIN companies c ON c.id=w.company_id AND TRIM(COALESCE(c.rma,''))<>''
JOIN parkings p ON p.id=w.parking_id AND TRIM(COALESCE(p.registration_number,''))<>''
LEFT JOIN brands b ON b.id=p.brand_id
LEFT JOIN routes r ON r.id=w.route_id
$PD
WHERE w.deleted_at IS NULL AND w.created_at >= '$Window';
"@

# waybill1ads -> WB_BUS / WB_TROLLEYBUS (type='ebus').
$q_1ads = @"
SELECT w.id, '1D', CASE WHEN w.type='ebus' THEN 'WB_TROLLEYBUS' ELSE 'WB_BUS' END, NULL,
  c.rma, c.name, p.registration_number, COALESCE(NULLIF(TRIM(p.brand_name),''), b.name),
  d.rma, d.full_name, NULL, NULL,
  NULLIF(TRIM(CONCAT_WS(' - ', NULLIF(TRIM(r.name_a),''), NULLIF(TRIM(r.name_b),''))),''),
  w.number, w.exit_date, w.entry_date, w.indication_counter_exit, w.indication_counter_entry,
  w.schedule, w.special_mark, w.created_at, NULL
FROM waybill1ads w
JOIN companies c ON c.id=w.company_id AND TRIM(COALESCE(c.rma,''))<>''
JOIN parkings p ON p.id=w.parking_id AND TRIM(COALESCE(p.registration_number,''))<>''
LEFT JOIN brands b ON b.id=p.brand_id
LEFT JOIN routes r ON r.id=w.route_id
$PD
WHERE w.deleted_at IS NULL AND w.created_at >= '$Window';
"@

# waybill2bs -> WB_TRUCK. Нет route_id (маршрут из directions.title), нет schedule.
$q_2bs = @"
SELECT w.id, '2B', 'WB_TRUCK', NULL,
  c.rma, c.name, p.registration_number, COALESCE(NULLIF(TRIM(p.brand_name),''), b.name),
  d.rma, d.full_name, NULL, NULL,
  NULLIF(TRIM(dir.title),''),
  w.number, w.exit_date, w.entry_date, w.indication_counter_exit, w.indication_counter_entry,
  NULL, w.special_mark, w.created_at, NULL
FROM waybill2bs w
JOIN companies c ON c.id=w.company_id AND TRIM(COALESCE(c.rma,''))<>''
JOIN parkings p ON p.id=w.parking_id AND TRIM(COALESCE(p.registration_number,''))<>''
LEFT JOIN brands b ON b.id=p.brand_id
LEFT JOIN directions dir ON dir.id=w.direction_id
$PD
WHERE w.deleted_at IS NULL AND w.created_at >= '$Window';
"@

# waybill5bbms -> WB_TRUCK_INTL. Водители напрямую (first/second_driver_id), номер = bba_number.
$q_5bbms = @"
SELECT w.id, '5F', 'WB_TRUCK_INTL', NULL,
  c.rma, c.name, p.registration_number, COALESCE(NULLIF(TRIM(p.brand_name),''), b.name),
  d1.rma, d1.full_name, d2.rma, d2.full_name,
  NULL,
  w.bba_number, w.exit_date, w.entry_date, w.indication_counter_exit, w.indication_counter_entry,
  NULL, w.special_mark, w.created_at, NULL
FROM waybill5bbms w
JOIN companies c ON c.id=w.company_id AND TRIM(COALESCE(c.rma,''))<>''
JOIN parkings p ON p.id=w.parking_id AND TRIM(COALESCE(p.registration_number,''))<>''
LEFT JOIN brands b ON b.id=p.brand_id
JOIN drivers d1 ON d1.id=w.first_driver_id AND TRIM(COALESCE(d1.rma,''))<>'' AND CHAR_LENGTH(TRIM(d1.rma))<=10
LEFT JOIN drivers d2 ON d2.id=w.second_driver_id AND TRIM(COALESCE(d2.rma,''))<>'' AND CHAR_LENGTH(TRIM(d2.rma))<=10
WHERE w.deleted_at IS NULL AND w.created_at >= '$Window';
"@

$queries = [ordered]@{ 'wb_3cs'=$q_3cs; 'wb_1as'=$q_1as; 'wb_1ads'=$q_1ads; 'wb_2bs'=$q_2bs; 'wb_5bbms'=$q_5bbms }

Write-Host "== Окно миграции: created_at >= $Window ==" -ForegroundColor Cyan

Write-Host '== BEFORE (архивные мигрированные ПЛ) ==' -ForegroundColor Cyan
Invoke-Pg "SELECT 'ARCHIVED/MIGRATED', count(*) FROM waybill WHERE source='MIGRATED';"

Write-Host '== 1) staging DDL Ф5 ==' -ForegroundColor Cyan
Invoke-PgFile (Join-Path $SCRIPTDIR '14_staging_phase5_ddl.sql')

Write-Host '== 2) выгрузка legacy -> stg_wb5 (все типы) ==' -ForegroundColor Cyan
foreach ($name in $queries.Keys) { Write-Host "   $name"; Load-Table $name $queries[$name] }

Write-Host '== staging загружено (строк) ==' -ForegroundColor DarkCyan
Invoke-Pg "SELECT 'stg_wb5', count(*) FROM stg_wb5;"

Write-Host '== 3) ФАЗА 5 -> waybill (INSERT ... ON CONFLICT DO NOTHING) ==' -ForegroundColor Cyan
Invoke-PgFile (Join-Path $SCRIPTDIR '15_phase5_waybills.sql')

Write-Host '== AFTER (архивные мигрированные ПЛ, по типам) ==' -ForegroundColor Cyan
Invoke-Pg "SELECT waybill_type, count(*) FROM waybill WHERE source='MIGRATED' GROUP BY waybill_type ORDER BY 2 DESC;"

Write-Host 'Готово Ф5.' -ForegroundColor Green
