# =============================================================================
# Migration orchestrator: legacy "Rohkhat" MySQL -> e-Waybill PostgreSQL.
# PHASE 5b - work metrics of the archived waybills loaded by phase 5
# (laps, revenue/kassa, work days, fuel lines). Target DB: waybill,
# tables work_day and fuel_record. INSERT only: no UPDATE/DELETE on waybill.
#
# Pipeline (same as phase 5, UTF-8 safe under PowerShell 5.1):
#   1) mysql batch (-N -B, utf8mb4); legacy JSON TEXT (work_days, fuels) is
#      unpacked by MySQL 8 JSON_TABLE right in the export query -> TSV;
#   2) docker cp mysql -> host -> postgres (bytes, not PS strings);
#   3) server-side COPY -> stg_wd5b (work days) / stg_fr5b (fuel lines);
#   4) 17_phase5b_metrics.sql: INSERT ... SELECT, bound to the waybill by
#      number = 'MG'||src_code||legacy_id, idempotent (NOT EXISTS + ON CONFLICT).
#
# Run AFTER run_phase5.ps1 with the same -Window (rows of waybills that are not
# in the archive are simply skipped). Safe to re-run.
# Usage: powershell -ExecutionPolicy Bypass -File .\run_phase5b.ps1 [-Window 2025-09-21]
# NOTE: this file must stay pure ASCII (Windows PowerShell 5.1 parser).
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

if ($Window -notmatch '^\d{4}-\d{2}-\d{2}$') { throw "Window must be YYYY-MM-DD, got '$Window'" }

function Invoke-PgFile([string]$file) {
  $base = Split-Path $file -Leaf
  docker cp $file "${PG}:/tmp/$base"
  docker exec $PG psql -U epd -d $DB -v ON_ERROR_STOP=1 -f "/tmp/$base"
  if ($LASTEXITCODE -ne 0) { throw "psql failed on $base" }
}
function Invoke-Pg([string]$sql) { docker exec $PG psql -U epd -d $DB -tA -c $sql }

# Export one query -> TSV -> COPY into the given staging table.
function Load-Table([string]$name, [string]$table, [string]$query) {
  $qfile = Join-Path $HOSTTMP "q5b_$name.sql"
  Set-Content -Path $qfile -Value ($query.Replace('__WINDOW__', $Window)) -Encoding ascii
  docker cp $qfile "${MYSQL}:/tmp/q5b_$name.sql"
  # tr -d '\015\000': drop bare CR and NUL from legacy free text, otherwise COPY fails.
  docker exec $MYSQL sh -c "mysql --default-character-set=utf8mb4 -urohkhat -psecret rohkhat -N -B < /tmp/q5b_$name.sql 2>/tmp/q5b_$name.err | tr -d '\015\000' > /tmp/q5b_$name.tsv"
  $err = docker exec $MYSQL sh -c "grep -v 'Using a password' /tmp/q5b_$name.err || true"
  if ($err) { throw "mysql export $name failed: $err" }
  docker cp "${MYSQL}:/tmp/q5b_$name.tsv" "$HOSTTMP\q5b_$name.tsv"
  docker cp "$HOSTTMP\q5b_$name.tsv" "${PG}:/tmp/q5b_$name.tsv"
  docker exec $PG psql -U epd -d $DB -v ON_ERROR_STOP=1 -c "SET client_encoding='UTF8'; COPY $table FROM '/tmp/q5b_$name.tsv' WITH (FORMAT text, NULL 'NULL');"
  if ($LASTEXITCODE -ne 0) { throw "COPY $name -> $table failed" }
}

# --- work day exports: 14 columns, order = stg_wd5b ---------------------------
# src_code, legacy_id, seq, work_date, exit_time, entry_time, odo_exit, odo_entry,
# laps, revenue, conditioner_time, client_time, work_minutes, created_at

# 1-AD bus/trolleybus: flat columns, one work day per waybill (legacy has no work_days).
# Revenue = earning + kassa (legacy ReportCrudController: "way.earning + way.kassa AS daromad").
$wd_1ads = @'
SELECT '1D', w.id, 1, DATE_FORMAT(w.created_at, '%Y-%m-%d'), w.exit_date, w.entry_date,
  w.indication_counter_exit, w.indication_counter_entry, w.number_lap,
  CASE WHEN w.earning IS NULL AND w.kassa IS NULL THEN NULL ELSE IFNULL(w.earning, 0) + IFNULL(w.kassa, 0) END,
  w.conditioner_time, w.client_time, w.work_time_minutes, w.created_at
FROM waybill1ads w
WHERE w.deleted_at IS NULL AND w.created_at >= '__WINDOW__'
  AND (w.number_lap IS NOT NULL OR w.earning IS NOT NULL OR w.kassa IS NOT NULL
       OR w.work_time_minutes IS NOT NULL OR w.entry_date IS NOT NULL);
'@

# 1-A minibus / 3-C car-taxi: JSON array work_days -> one row per element.
# kassa belongs to the whole waybill -> put on day 1 (or on a day-less row if work_days is empty).
$wd_days = @'
SELECT '__SRC__', w.id, j.seq, j.dt, j.et, j.nt, j.ice, j.icn, j.laps,
  CASE WHEN j.seq IS NULL OR j.seq = 1 THEN w.kassa END,
  j.cond, j.ct, j.wtm, w.created_at
FROM __TABLE__ w
LEFT JOIN JSON_TABLE(IF(JSON_VALID(w.work_days), w.work_days, '[]'), '$[*]' COLUMNS(
  seq  FOR ORDINALITY,
  dt   VARCHAR(40) PATH '$.date',
  et   VARCHAR(40) PATH '$.exit_time',
  nt   VARCHAR(40) PATH '$.entry_time',
  ice  VARCHAR(40) PATH '$.indication_counter_exit',
  icn  VARCHAR(40) PATH '$.indication_counter_entry',
  laps VARCHAR(40) PATH '$.laps',
  cond VARCHAR(40) PATH '$.conditioner_time',
  ct   VARCHAR(40) PATH '$.client_time',
  wtm  VARCHAR(40) PATH '$.workTimeInMinutes')) j ON TRUE
WHERE w.deleted_at IS NULL AND w.created_at >= '__WINDOW__'
  AND (j.seq IS NOT NULL OR w.kassa > 0);
'@
$wd_1as = $wd_days.Replace('__SRC__', '1A').Replace('__TABLE__', 'waybill1as')
$wd_3cs = $wd_days.Replace('__SRC__', '3C').Replace('__TABLE__', 'waybill3cs')

# --- fuel line exports: 12 columns, order = stg_fr5b ---------------------------
# src_code, legacy_id, day_seq, line_seq, fuel_id, fuel_given, additional,
# remain_before_exit, remain_entry, coef_below_0, be_given, returned
$FUELCOLS = @'
  seq  FOR ORDINALITY,
  fid  VARCHAR(40) PATH '$.fuel_id',
  fg   VARCHAR(40) PATH '$.fuel_given',
  ad   VARCHAR(40) PATH '$.additional',
  rbe  VARCHAR(40) PATH '$.remain_fuel_before_exit',
  re   VARCHAR(40) PATH '$.remain_fuel_entry',
  cb0  VARCHAR(40) PATH '$.coef_below_0',
  bg   VARCHAR(40) PATH '$.be_given',
  rt   VARCHAR(40) PATH '$.returned'
'@

# 1-AD and 5B-BM: JSON array `fuels` on the waybill itself.
$fr_flat = @'
SELECT '__SRC__', w.id, NULL, f.seq, f.fid, f.fg, f.ad, f.rbe, f.re, f.cb0, f.bg, f.rt
FROM __TABLE__ w,
  JSON_TABLE(IF(JSON_VALID(w.fuels), w.fuels, '[]'), '$[*]' COLUMNS(
__FUELCOLS__)) f
WHERE w.deleted_at IS NULL AND w.created_at >= '__WINDOW__';
'@
$fr_1ads  = $fr_flat.Replace('__SRC__', '1D').Replace('__TABLE__', 'waybill1ads').Replace('__FUELCOLS__', $FUELCOLS)
$fr_5bbms = $fr_flat.Replace('__SRC__', '5F').Replace('__TABLE__', 'waybill5bbms').Replace('__FUELCOLS__', $FUELCOLS)

# 1-A / 3-C / 2-B: fuel lines inside work_days[].fuels, which is a JSON-encoded STRING
# (sometimes a real array) -> second JSON_TABLE over the unquoted string.
$fr_days = @'
SELECT '__SRC__', w.id, d.seq, f.seq, f.fid, f.fg, f.ad, f.rbe, f.re, f.cb0, f.bg, f.rt
FROM __TABLE__ w,
  JSON_TABLE(IF(JSON_VALID(w.work_days), w.work_days, '[]'), '$[*]' COLUMNS(
    seq FOR ORDINALITY,
    fu  JSON PATH '$.fuels')) d,
  JSON_TABLE(CASE WHEN JSON_TYPE(d.fu) = 'ARRAY' THEN CAST(d.fu AS CHAR)
                  WHEN JSON_TYPE(d.fu) = 'STRING' AND JSON_VALID(JSON_UNQUOTE(d.fu)) THEN JSON_UNQUOTE(d.fu)
                  ELSE '[]' END, '$[*]' COLUMNS(
__FUELCOLS__)) f
WHERE w.deleted_at IS NULL AND w.created_at >= '__WINDOW__' AND w.work_days LIKE '%fuel_given%';
'@
$fr_1as = $fr_days.Replace('__SRC__', '1A').Replace('__TABLE__', 'waybill1as').Replace('__FUELCOLS__', $FUELCOLS)
$fr_3cs = $fr_days.Replace('__SRC__', '3C').Replace('__TABLE__', 'waybill3cs').Replace('__FUELCOLS__', $FUELCOLS)
$fr_2bs = $fr_days.Replace('__SRC__', '2B').Replace('__TABLE__', 'waybill2bs').Replace('__FUELCOLS__', $FUELCOLS)

$loads = @(
  @('wd_1ads',  'stg_wd5b', $wd_1ads),
  @('wd_1as',   'stg_wd5b', $wd_1as),
  @('wd_3cs',   'stg_wd5b', $wd_3cs),
  @('fr_1ads',  'stg_fr5b', $fr_1ads),
  @('fr_5bbms', 'stg_fr5b', $fr_5bbms),
  @('fr_1as',   'stg_fr5b', $fr_1as),
  @('fr_3cs',   'stg_fr5b', $fr_3cs),
  @('fr_2bs',   'stg_fr5b', $fr_2bs)
)

Write-Host "== Phase 5b window: created_at >= $Window ==" -ForegroundColor Cyan

Write-Host '== BEFORE (rows attached to MIGRATED waybills) ==' -ForegroundColor Cyan
Invoke-Pg "SELECT 'work_day', count(*) FROM work_day d JOIN waybill w ON w.id=d.waybill_id AND w.source='MIGRATED' UNION ALL SELECT 'fuel_record', count(*) FROM fuel_record f JOIN waybill w ON w.id=f.waybill_id AND w.source='MIGRATED';"

Write-Host '== 1) staging DDL 5b ==' -ForegroundColor Cyan
Invoke-PgFile (Join-Path $SCRIPTDIR '16_staging_phase5b_ddl.sql')

Write-Host '== 2) legacy export -> staging ==' -ForegroundColor Cyan
foreach ($l in $loads) {
  $t0 = Get-Date
  Load-Table $l[0] $l[1] $l[2]
  Write-Host ("   {0} -> {1} ({2:N0} s)" -f $l[0], $l[1], ((Get-Date) - $t0).TotalSeconds)
}
Invoke-Pg "SELECT 'stg_wd5b', src_code, count(*) FROM stg_wd5b GROUP BY src_code UNION ALL SELECT 'stg_fr5b', src_code, count(*) FROM stg_fr5b GROUP BY src_code ORDER BY 1, 2;"

Write-Host '== 3) PHASE 5b -> work_day, fuel_record (INSERT only) ==' -ForegroundColor Cyan
Invoke-PgFile (Join-Path $SCRIPTDIR '17_phase5b_metrics.sql')

Write-Host '== AFTER (by waybill type) ==' -ForegroundColor Cyan
Invoke-Pg "SELECT w.waybill_type, count(DISTINCT d.waybill_id) AS waybills, count(*) AS days, COALESCE(sum(d.laps),0) AS laps, COALESCE(sum(d.revenue),0) AS revenue FROM work_day d JOIN waybill w ON w.id=d.waybill_id AND w.source='MIGRATED' GROUP BY 1 ORDER BY 1;"
Invoke-Pg "SELECT w.waybill_type, f.fuel_type, count(*) AS lines, COALESCE(sum(f.fuel_given),0) AS given FROM fuel_record f JOIN waybill w ON w.id=f.waybill_id AND w.source='MIGRATED' GROUP BY 1, 2 ORDER BY 1, 2;"

Write-Host 'Phase 5b done.' -ForegroundColor Green
