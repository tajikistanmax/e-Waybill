# Field usage in the legacy production DB (rohkhat.tj MySQL copy, container rohkhattjralavel-db-1).
# Active = referenced by a waybill created in the 12 months before the dump. Output: %TEMP%\field-usage\usage.tsv
# Legacy tables are only read: work tables live in a scratch schema field_usage dropped at the end.
# Report: spec/ (field usage analysis, 2026-09-24).
$ErrorActionPreference = 'Continue'
$db = 'rohkhattjralavel-db-1'
$out = Join-Path $env:TEMP 'field-usage'
New-Item -ItemType Directory -Force $out | Out-Null
$since = '2025-08-07'

function Cols($t) {
  $rows = docker exec $db mysql -uroot -psecret rohkhat -N -e "SELECT COLUMN_NAME, DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='rohkhat' AND TABLE_NAME='$t' ORDER BY ORDINAL_POSITION;" 2>$null
  $rows | ForEach-Object { $p = $_ -split "`t"; [pscustomobject]@{ n = $p[0]; t = $p[1] } }
}

function Meaningful($c, $type) {
  switch -regex ($type) {
    'char|text' { return "($c IS NOT NULL AND TRIM($c) NOT IN ('','0','-','--','.','NULL','null','0000-00-00'))" }
    'int|double|float|decimal' { return "($c IS NOT NULL AND $c <> 0)" }
    'date|time' { return "($c IS NOT NULL AND $c > '1971-01-01')" }
    'blob' { return "($c IS NOT NULL AND LENGTH($c) > 0)" }
    'json' { return "($c IS NOT NULL AND LENGTH($c) > 4)" }
    default { return "($c IS NOT NULL)" }
  }
}

# Active sets: referenced by any waybill created in the last 12 months before the dump.
$prep = @"
SET SESSION sql_mode = '';
DROP DATABASE IF EXISTS field_usage;
CREATE DATABASE field_usage;
CREATE TABLE field_usage.act_company (id BIGINT PRIMARY KEY);
CREATE TABLE field_usage.act_parking (id BIGINT PRIMARY KEY);
CREATE TABLE field_usage.act_driver (id BIGINT PRIMARY KEY);
CREATE TABLE field_usage.act_employee (id BIGINT PRIMARY KEY);
INSERT IGNORE INTO field_usage.act_company SELECT DISTINCT company_id FROM waybill1ads WHERE created_at >= '$since';
INSERT IGNORE INTO field_usage.act_company SELECT DISTINCT company_id FROM waybill1as WHERE created_at >= '$since';
INSERT IGNORE INTO field_usage.act_company SELECT DISTINCT company_id FROM waybill2bs WHERE created_at >= '$since';
INSERT IGNORE INTO field_usage.act_company SELECT DISTINCT company_id FROM waybill3cs WHERE created_at >= '$since';
INSERT IGNORE INTO field_usage.act_company SELECT DISTINCT company_id FROM waybill5bbms WHERE created_at >= '$since';
INSERT IGNORE INTO field_usage.act_parking SELECT DISTINCT parking_id FROM waybill1ads WHERE created_at >= '$since';
INSERT IGNORE INTO field_usage.act_parking SELECT DISTINCT parking_id FROM waybill1as WHERE created_at >= '$since';
INSERT IGNORE INTO field_usage.act_parking SELECT DISTINCT parking_id FROM waybill2bs WHERE created_at >= '$since';
INSERT IGNORE INTO field_usage.act_parking SELECT DISTINCT parking_id FROM waybill3cs WHERE created_at >= '$since';
INSERT IGNORE INTO field_usage.act_parking SELECT DISTINCT parking_id FROM waybill5bbms WHERE created_at >= '$since';
INSERT IGNORE INTO field_usage.act_driver SELECT DISTINCT timesheet_id FROM waybill1ads WHERE created_at >= '$since';
INSERT IGNORE INTO field_usage.act_driver SELECT DISTINCT timesheet_id FROM waybill1as WHERE created_at >= '$since';
INSERT IGNORE INTO field_usage.act_driver SELECT DISTINCT timesheet_id FROM waybill2bs WHERE created_at >= '$since';
INSERT IGNORE INTO field_usage.act_driver SELECT DISTINCT timesheet_id FROM waybill3cs WHERE created_at >= '$since';
INSERT IGNORE INTO field_usage.act_driver SELECT DISTINCT first_driver_id FROM waybill5bbms WHERE created_at >= '$since';
INSERT IGNORE INTO field_usage.act_driver SELECT DISTINCT second_driver_id FROM waybill5bbms WHERE created_at >= '$since' AND second_driver_id IS NOT NULL;
INSERT IGNORE INTO field_usage.act_employee SELECT DISTINCT dispatcher_id FROM waybill1ads WHERE created_at >= '$since';
INSERT IGNORE INTO field_usage.act_employee SELECT DISTINCT doctor_id FROM waybill1ads WHERE created_at >= '$since';
INSERT IGNORE INTO field_usage.act_employee SELECT DISTINCT mechanic_id FROM waybill1ads WHERE created_at >= '$since';
INSERT IGNORE INTO field_usage.act_employee SELECT DISTINCT dispatcher_id FROM waybill1as WHERE created_at >= '$since';
INSERT IGNORE INTO field_usage.act_employee SELECT DISTINCT doctor_id FROM waybill1as WHERE created_at >= '$since';
INSERT IGNORE INTO field_usage.act_employee SELECT DISTINCT mechanic_id FROM waybill1as WHERE created_at >= '$since';
INSERT IGNORE INTO field_usage.act_employee SELECT DISTINCT dispatcher_id FROM waybill2bs WHERE created_at >= '$since';
INSERT IGNORE INTO field_usage.act_employee SELECT DISTINCT doctor_id FROM waybill2bs WHERE created_at >= '$since';
INSERT IGNORE INTO field_usage.act_employee SELECT DISTINCT mechanic_id FROM waybill2bs WHERE created_at >= '$since';
INSERT IGNORE INTO field_usage.act_employee SELECT DISTINCT dispatcher_id FROM waybill3cs WHERE created_at >= '$since';
INSERT IGNORE INTO field_usage.act_employee SELECT DISTINCT doctor_id FROM waybill3cs WHERE created_at >= '$since';
INSERT IGNORE INTO field_usage.act_employee SELECT DISTINCT mechanic_id FROM waybill3cs WHERE created_at >= '$since';
INSERT IGNORE INTO field_usage.act_employee SELECT DISTINCT employee_kassa_id FROM waybill3cs WHERE created_at >= '$since';
INSERT IGNORE INTO field_usage.act_employee SELECT DISTINCT dispatcher_id FROM waybill5bbms WHERE created_at >= '$since';
INSERT IGNORE INTO field_usage.act_employee SELECT DISTINCT doctor_id FROM waybill5bbms WHERE created_at >= '$since';
INSERT IGNORE INTO field_usage.act_employee SELECT DISTINCT mechanic_id FROM waybill5bbms WHERE created_at >= '$since';
"@

$tables = [ordered]@{ companies = 'field_usage.act_company'; parkings = 'field_usage.act_parking'; drivers = 'field_usage.act_driver'; employees = 'field_usage.act_employee' }
$sql = New-Object System.Text.StringBuilder
[void]$sql.AppendLine($prep)
[void]$sql.AppendLine("SELECT 'ACTIVE', 'companies', COUNT(*) FROM field_usage.act_company UNION ALL SELECT 'ACTIVE','parkings',COUNT(*) FROM field_usage.act_parking UNION ALL SELECT 'ACTIVE','drivers',COUNT(*) FROM field_usage.act_driver UNION ALL SELECT 'ACTIVE','employees',COUNT(*) FROM field_usage.act_employee;")
foreach ($t in $tables.Keys) {
  $act = $tables[$t]
  foreach ($c in (Cols $t)) {
    $col = '`' + $c.n + '`'
    $m = Meaningful ("x.$col") $c.t
    $mAll = Meaningful $col $c.t
    $valExpr = if ($c.t -match 'blob') { "LENGTH(x.$col)" } else { "x.$col" }
    [void]$sql.AppendLine(@"
SELECT 'COL', '$t', '$($c.n)', '$($c.t)',
  (SELECT COUNT(*) FROM $t WHERE deleted_at IS NULL),
  (SELECT COALESCE(SUM($mAll),0) FROM $t WHERE deleted_at IS NULL),
  (SELECT COUNT(*) FROM $t x JOIN $act a ON a.id = x.id),
  (SELECT COALESCE(SUM($m),0) FROM $t x JOIN $act a ON a.id = x.id),
  (SELECT COUNT(DISTINCT $valExpr) FROM $t x JOIN $act a ON a.id = x.id WHERE $m),
  (SELECT COALESCE(MAX(k),0) FROM (SELECT COUNT(*) k FROM $t x JOIN $act a ON a.id = x.id WHERE $m GROUP BY $valExpr) g),
  (SELECT LEFT(CAST($valExpr AS CHAR),40) FROM $t x JOIN $act a ON a.id = x.id WHERE $m GROUP BY $valExpr ORDER BY COUNT(*) DESC LIMIT 1);
"@)
  }
}
# Vehicles by transport type among active (fill of trailer / intl / capacity / carrying per type).
$pcols = Cols 'parkings'
$seg = New-Object System.Text.StringBuilder
foreach ($c in $pcols) {
  $col = '`' + $c.n + '`'
  $m = Meaningful ("x.$col") $c.t
  [void]$sql.AppendLine("SELECT 'SEG', 'parkings', '$($c.n)', x.transport_type_id, COUNT(*), SUM($m) FROM parkings x JOIN field_usage.act_parking a ON a.id = x.id GROUP BY x.transport_type_id;")
}
[void]$sql.AppendLine('DROP DATABASE field_usage;')
$f = Join-Path $out 'usage.sql'
[IO.File]::WriteAllText($f, $sql.ToString(), (New-Object System.Text.UTF8Encoding($false)))
docker cp $f "${db}:/tmp/usage.sql" | Out-Null
docker exec $db sh -c "mysql -uroot -psecret --default-character-set=utf8mb4 rohkhat -N < /tmp/usage.sql > /tmp/usage.tsv 2>/tmp/usage.err"
docker cp "${db}:/tmp/usage.tsv" (Join-Path $out 'usage.tsv') | Out-Null
docker exec $db sh -c "cat /tmp/usage.err | grep -v Warning | head -5"
"rows: " + (Get-Content (Join-Path $out 'usage.tsv')).Count
