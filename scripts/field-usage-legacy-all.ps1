# Field usage of ALL work tables of the legacy production DB (rohkhat.tj MySQL copy,
# container rohkhattjralavel-db-1, dump 07.08.2026). Companion of field-usage-legacy.ps1
# (that one covers companies / parkings / drivers / employees; they are NOT repeated here).
#
# What it computes, per work table and per column:
#   total rows (not deleted), filled among them;
#   "active" rows, filled among active, "real" filled among active (text: without the '1' stub),
#   filled among active created in the last 3 months (to spot fields people stopped using),
#   number of distinct values and the most frequent value (+ its count).
# Active = waybills / consignments / malumotnoma created in the 12 months before the dump
# (created_at >= $Since); dictionaries = rows referenced by active documents when such a link
# exists, otherwise all non-deleted rows.
# Segments: 1-AD by `type` (bus/ebus), 3-C by `type_service` (1 taxi / 2 route / 3 hourly),
# 2-B by `type_of_shipment`, cargo_waybills by `type`.
# JSON / JSON-in-text columns are exploded (MySQL 8 JSON_TABLE): per key -> elements, filled,
# real, distinct, top value; nested work_days[].fuels as well; array length distribution.
#
# Legacy tables are only READ. Work tables live in the scratch schema `field_usage`,
# which is dropped at the end.
# Output: %TEMP%\field-usage-all\usage-all.tsv (raw) and usage-all.csv (with percentages).
# Report: spec/ (full legacy DB field usage analysis, 2026-09-24).
# NOTE: keep this file pure ASCII (Windows PowerShell 5.1 parser).
param(
  [string]$Since = '2025-08-07',
  [string]$Recent = '2026-05-07',
  [string]$Db = 'rohkhattjralavel-db-1',
  [switch]$DryRun   # only write %TEMP%\field-usage-all\usage-all.sql, do not run it
)
$ErrorActionPreference = 'Continue'
$out = Join-Path $env:TEMP 'field-usage-all'
New-Item -ItemType Directory -Force $out | Out-Null

function Q([string]$sql) {
  docker exec $Db mysql -uroot -psecret --default-character-set=utf8mb4 rohkhat -N -e $sql 2>$null
}
function Cols([string]$t) {
  Q "SELECT COLUMN_NAME, DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='rohkhat' AND TABLE_NAME='$t' ORDER BY ORDINAL_POSITION;" |
    ForEach-Object { $p = $_ -split "`t"; [pscustomobject]@{ n = $p[0]; t = $p[1] } }
}

# Filled = not null / not empty / not a zero stub. Real = filled and (text) not the '1' stub.
function Filled([string]$c, [string]$type) {
  if ($type -eq 'time') { return "($c IS NOT NULL AND $c <> '00:00:00')" }
  if ($type -match '^(date|datetime|timestamp)$') { return "($c IS NOT NULL AND $c > '1971-01-01')" }
  if ($type -match 'char|text') { return "($c IS NOT NULL AND TRIM($c) NOT IN ('','0','-','--','.','NULL','null','[]','{}','[null]','0000-00-00','00:00','00:00:00'))" }
  if ($type -match 'int|double|float|decimal') { return "($c IS NOT NULL AND $c <> 0)" }
  if ($type -match 'blob') { return "($c IS NOT NULL AND LENGTH($c) > 0)" }
  if ($type -eq 'json') { return "($c IS NOT NULL AND LENGTH($c) > 4)" }
  return "($c IS NOT NULL)"
}
function Real([string]$c, [string]$type) {
  $f = Filled $c $type
  if ($type -match 'char|text') { return "($f AND TRIM($c) <> '1')" }
  return $f
}

# ---------------------------------------------------------------- table catalogue
# group: WB (waybill), CN (consignment), ML (malumotnoma), DICT (dictionary), LINK
# act:   SQL that selects the ACTIVE rows of the table (alias x), materialised as field_usage.a_<t>
# seg:   optional segment column
$S = $Since
$tables = [ordered]@{
  'waybill1ads'        = @{ g='WB';   seg='type';             act="SELECT x.* FROM waybill1ads x WHERE x.deleted_at IS NULL AND x.created_at >= '$S'" }
  'waybill1as'         = @{ g='WB';   seg='';                 act="SELECT x.* FROM waybill1as x WHERE x.deleted_at IS NULL AND x.created_at >= '$S'" }
  'waybill2bs'         = @{ g='WB';   seg='type_of_shipment'; act="SELECT x.* FROM waybill2bs x WHERE x.deleted_at IS NULL AND x.created_at >= '$S'" }
  'waybill3cs'         = @{ g='WB';   seg='type_service';     act="SELECT x.* FROM waybill3cs x WHERE x.deleted_at IS NULL AND x.created_at >= '$S'" }
  'waybill5bbms'       = @{ g='WB';   seg='';                 act="SELECT x.* FROM waybill5bbms x WHERE x.deleted_at IS NULL AND x.created_at >= '$S'" }
  'waybill4mbms'       = @{ g='WB';   seg='';                 act="SELECT x.* FROM waybill4mbms x WHERE x.created_at >= '$S'" }
  'waybill_work_days'  = @{ g='WB';   seg='';                 act="SELECT x.* FROM waybill_work_days x WHERE x.deleted_at IS NULL AND x.created_at >= '$S'" }
  'waybill_plans'      = @{ g='WB';   seg='';                 act="SELECT x.* FROM waybill_plans x" }
  'cargo_waybills'     = @{ g='CN';   seg='type';             act="SELECT x.* FROM cargo_waybills x WHERE x.deleted_at IS NULL AND x.created_at >= '$S'" }
  'cargo_waybill5bbms' = @{ g='CN';   seg='';                 act="SELECT x.* FROM cargo_waybill5bbms x WHERE x.deleted_at IS NULL" }
  'malumotnomas'       = @{ g='ML';   seg='transport_type_id'; act="SELECT x.* FROM malumotnomas x WHERE x.deleted_at IS NULL AND x.created_at >= '$S'" }
  'rmalumotnomas'      = @{ g='ML';   seg='';                 act="SELECT x.* FROM rmalumotnomas x JOIN field_usage.a_malumotnomas m ON m.id = x.malumotnoma_id" }
  'routemalumotnomas'  = @{ g='ML';   seg='';                 act="SELECT x.* FROM routemalumotnomas x WHERE x.id IN (SELECT route_id FROM field_usage.a_rmalumotnomas)" }
  'routes'             = @{ g='DICT'; seg='';                 act="SELECT x.* FROM routes x JOIN field_usage.act_route a ON a.id = x.id" }
  'brands'             = @{ g='DICT'; seg='';                 act="SELECT x.* FROM brands x JOIN field_usage.act_brand a ON a.id = x.id" }
  'directions'         = @{ g='DICT'; seg='';                 act="SELECT x.* FROM directions x JOIN field_usage.act_direction a ON a.id = x.id WHERE x.deleted_at IS NULL" }
  'clients'            = @{ g='DICT'; seg='type';             act="SELECT x.* FROM clients x JOIN field_usage.act_client a ON a.id = x.id WHERE x.deleted_at IS NULL" }
  'cargos'             = @{ g='DICT'; seg='';                 act="SELECT x.* FROM cargos x JOIN field_usage.act_cargo a ON a.id = x.id WHERE x.deleted_at IS NULL" }
  'external_countries' = @{ g='DICT'; seg='';                 act="SELECT x.* FROM external_countries x JOIN field_usage.act_country a ON a.id = x.id WHERE x.deleted_at IS NULL" }
  'external_cities'    = @{ g='DICT'; seg='';                 act="SELECT x.* FROM external_cities x JOIN field_usage.act_ext_city a ON a.id = x.id WHERE x.deleted_at IS NULL" }
  'tariffs'            = @{ g='DICT'; seg='';                 act="SELECT x.* FROM tariffs x" }
  'fuels'              = @{ g='DICT'; seg='';                 act="SELECT x.* FROM fuels x" }
  'fuel_winter_coef'   = @{ g='DICT'; seg='';                 act="SELECT x.* FROM fuel_winter_coef x" }
  'mountain_coef'      = @{ g='DICT'; seg='';                 act="SELECT x.* FROM mountain_coef x" }
  'city_coef'          = @{ g='DICT'; seg='';                 act="SELECT x.* FROM city_coef x" }
  'used_coef'          = @{ g='DICT'; seg='';                 act="SELECT x.* FROM used_coef x" }
  'drive_classes'      = @{ g='DICT'; seg='';                 act="SELECT x.* FROM drive_classes x" }
  'cities'             = @{ g='DICT'; seg='';                 act="SELECT x.* FROM cities x" }
  'regions'            = @{ g='DICT'; seg='';                 act="SELECT x.* FROM regions x" }
  'route_types'        = @{ g='DICT'; seg='';                 act="SELECT x.* FROM route_types x" }
  'transport_type'     = @{ g='DICT'; seg='';                 act="SELECT x.* FROM transport_type x" }
  'type_company'       = @{ g='DICT'; seg='';                 act="SELECT x.* FROM type_company x" }
  'ownerships'         = @{ g='DICT'; seg='';                 act="SELECT x.* FROM ownerships x" }
  'bill_types'         = @{ g='DICT'; seg='';                 act="SELECT x.* FROM bill_types x" }
  'phone_infos'        = @{ g='DICT'; seg='type';             act="SELECT x.* FROM phone_infos x WHERE x.deleted_at IS NULL" }
  'cars'               = @{ g='DICT'; seg='';                 act="SELECT x.* FROM cars x" }
  'number_driver'      = @{ g='DICT'; seg='';                 act="SELECT x.* FROM number_driver x" }
  'gps_data'           = @{ g='DICT'; seg='';                 act="SELECT x.* FROM gps_data x" }
  'trailers'           = @{ g='DICT'; seg='';                 act="SELECT x.* FROM trailers x" }
  'brand_types'        = @{ g='DICT'; seg='';                 act="SELECT x.* FROM brand_types x" }
  'parkings_left_fuels'= @{ g='DICT'; seg='';                 act="SELECT x.* FROM parkings_left_fuels x" }
  'driver_params'      = @{ g='DICT'; seg='';                 act="SELECT x.* FROM driver_params x" }
  'parking_driver'     = @{ g='LINK'; seg='';                 act="SELECT x.* FROM parking_driver x JOIN field_usage.act_parking a ON a.id = x.parking_id" }
  'company_has_relatedcompany' = @{ g='LINK'; seg='';         act="SELECT x.* FROM company_has_relatedcompany x" }
}

# JSON columns: table, column, kind (obj = array of objects, scal = array of scalars,
# days = array of objects whose `fuels` key holds a JSON-encoded array of fuel lines)
$jsonCols = @(
  @('waybill1ads', 'fuels', 'obj'),
  @('waybill1as', 'work_days', 'days'),
  @('waybill1as', 'fuel', 'obj'),
  @('waybill2bs', 'work_days', 'days'),
  @('waybill2bs', 'regions_id', 'scal'),
  @('waybill3cs', 'work_days', 'days'),
  @('waybill3cs', 'fuel', 'obj'),
  @('waybill3cs', 'regions_id', 'scal'),
  @('waybill5bbms', 'fuels', 'obj'),
  @('waybill5bbms', 'transit_countries_id', 'scal'),
  @('waybill_work_days', 'fuels', 'obj'),
  @('waybill_work_days', 'cargowaybills1_id', 'scal'),
  @('waybill_work_days', 'cargowaybills2_id', 'scal'),
  @('malumotnomas', 'routes', 'obj'),
  @('malumotnomas', 'auto_type', 'scal'),
  @('brands', 'fuel_100', 'obj'),
  @('brands', 'fuel_100_dushanbe', 'obj'),
  @('brands', 'fuel_hour', 'obj'),
  @('routes', 'week_days_earnings', 'obj')
)

# Service tables: only listed with row counts.
$service = @('users','roles','permissions','model_has_permissions','model_has_roles','role_has_permissions',
  'migrations','password_resets','personal_access_tokens','failed_jobs','log_ips','revisions','menu_items',
  'categories','settings','company_has_user','employee_has_user','region_has_user','bill_has_user',
  'user_clients','company_for_api','backup_drivers','backup_parkings','dummies','addresses')
$already = @('companies','parkings','drivers','employees')

$sql = New-Object System.Text.StringBuilder
function Add([string]$s) { [void]$sql.AppendLine($s) }

Add "SET SESSION sql_mode = '';"
Add "SET SESSION group_concat_max_len = 4096;"
Add "DROP DATABASE IF EXISTS field_usage;"
Add "CREATE DATABASE field_usage;"

# --- inventory of every table (exact row counts; group label)
$allTables = Q "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='rohkhat' ORDER BY TABLE_NAME;"
foreach ($t in $allTables) {
  $grp = if ($tables.Contains($t)) { $tables[$t].g } elseif ($service -contains $t) { 'SERVICE' } elseif ($already -contains $t) { 'DONE4' } else { 'OTHER' }
  Add "SELECT 'TAB', '$t', '$grp', COUNT(*) FROM ``$t``;"
}

# --- materialise active documents first (dictionary active sets depend on them)
$order = @('waybill1ads','waybill1as','waybill2bs','waybill3cs','waybill5bbms','waybill4mbms','waybill_work_days','waybill_plans',
  'cargo_waybills','cargo_waybill5bbms','malumotnomas','rmalumotnomas','routemalumotnomas')
foreach ($t in $order) { Add "CREATE TABLE field_usage.a_$t AS $($tables[$t].act);" }

# --- active sets for dictionaries (referenced by active documents)
Add @"
CREATE TABLE field_usage.act_route (id BIGINT PRIMARY KEY);
INSERT IGNORE INTO field_usage.act_route SELECT route_id FROM field_usage.a_waybill1ads WHERE route_id IS NOT NULL;
INSERT IGNORE INTO field_usage.act_route SELECT route_id FROM field_usage.a_waybill1as WHERE route_id IS NOT NULL;
INSERT IGNORE INTO field_usage.act_route SELECT route_id FROM field_usage.a_waybill3cs WHERE route_id IS NOT NULL;
CREATE TABLE field_usage.act_parking (id BIGINT PRIMARY KEY);
INSERT IGNORE INTO field_usage.act_parking SELECT parking_id FROM field_usage.a_waybill1ads;
INSERT IGNORE INTO field_usage.act_parking SELECT parking_id FROM field_usage.a_waybill1as;
INSERT IGNORE INTO field_usage.act_parking SELECT parking_id FROM field_usage.a_waybill2bs;
INSERT IGNORE INTO field_usage.act_parking SELECT parking_id FROM field_usage.a_waybill3cs;
INSERT IGNORE INTO field_usage.act_parking SELECT parking_id FROM field_usage.a_waybill5bbms;
CREATE TABLE field_usage.act_brand (id BIGINT PRIMARY KEY);
INSERT IGNORE INTO field_usage.act_brand SELECT p.brand_id FROM parkings p JOIN field_usage.act_parking a ON a.id = p.id WHERE p.brand_id IS NOT NULL;
CREATE TABLE field_usage.act_direction (id BIGINT PRIMARY KEY);
INSERT IGNORE INTO field_usage.act_direction SELECT direction_id FROM field_usage.a_waybill2bs WHERE direction_id IS NOT NULL;
CREATE TABLE field_usage.act_client (id BIGINT PRIMARY KEY);
INSERT IGNORE INTO field_usage.act_client SELECT client_id FROM field_usage.a_waybill1ads WHERE client_id IS NOT NULL;
INSERT IGNORE INTO field_usage.act_client SELECT client_id FROM field_usage.a_waybill1as WHERE client_id IS NOT NULL;
INSERT IGNORE INTO field_usage.act_client SELECT client_id FROM field_usage.a_waybill2bs WHERE client_id IS NOT NULL;
INSERT IGNORE INTO field_usage.act_client SELECT client_id FROM field_usage.a_waybill3cs WHERE client_id IS NOT NULL;
INSERT IGNORE INTO field_usage.act_client SELECT client_id FROM field_usage.a_waybill5bbms WHERE client_id IS NOT NULL;
INSERT IGNORE INTO field_usage.act_client SELECT client_id FROM field_usage.a_cargo_waybills WHERE client_id IS NOT NULL;
INSERT IGNORE INTO field_usage.act_client SELECT sender_id FROM field_usage.a_cargo_waybills WHERE sender_id IS NOT NULL;
INSERT IGNORE INTO field_usage.act_client SELECT receiver_id FROM field_usage.a_cargo_waybills WHERE receiver_id IS NOT NULL;
INSERT IGNORE INTO field_usage.act_client SELECT forwarder_id FROM field_usage.a_cargo_waybills WHERE forwarder_id IS NOT NULL;
CREATE TABLE field_usage.act_cargo (id BIGINT PRIMARY KEY);
INSERT IGNORE INTO field_usage.act_cargo SELECT cargo_id FROM field_usage.a_cargo_waybills WHERE cargo_id IS NOT NULL;
INSERT IGNORE INTO field_usage.act_cargo SELECT cargo_id FROM field_usage.a_waybill5bbms WHERE cargo_id IS NOT NULL;
INSERT IGNORE INTO field_usage.act_cargo SELECT cargo_id FROM field_usage.a_cargo_waybill5bbms WHERE cargo_id IS NOT NULL;
CREATE TABLE field_usage.act_country (id BIGINT PRIMARY KEY);
INSERT IGNORE INTO field_usage.act_country SELECT load_country_id FROM field_usage.a_waybill5bbms WHERE load_country_id IS NOT NULL;
INSERT IGNORE INTO field_usage.act_country SELECT unload_country_id FROM field_usage.a_waybill5bbms WHERE unload_country_id IS NOT NULL;
INSERT IGNORE INTO field_usage.act_country SELECT visa_country_id FROM field_usage.a_waybill5bbms WHERE visa_country_id IS NOT NULL;
CREATE TABLE field_usage.act_ext_city (id BIGINT PRIMARY KEY);
INSERT IGNORE INTO field_usage.act_ext_city SELECT load_city_id FROM field_usage.a_waybill5bbms WHERE load_city_id IS NOT NULL;
INSERT IGNORE INTO field_usage.act_ext_city SELECT unload_city_id FROM field_usage.a_waybill5bbms WHERE unload_city_id IS NOT NULL;
"@
foreach ($t in $tables.Keys) {
  if ($order -notcontains $t) { Add "CREATE TABLE field_usage.a_$t AS $($tables[$t].act);" }
}

# --- per table: totals (all non-deleted) and active aggregates in single scans
foreach ($t in $tables.Keys) {
  $cols = @(Cols $t)
  if ($cols.Count -eq 0) { continue }
  $hasDel = @($cols | Where-Object { $_.n -eq 'deleted_at' }).Count -gt 0
  $hasCreated = @($cols | Where-Object { $_.n -eq 'created_at' }).Count -gt 0
  $where = if ($hasDel) { 'WHERE x.deleted_at IS NULL' } else { '' }
  $allSums = ($cols | ForEach-Object { 'COALESCE(SUM(' + (Filled ('x.`' + $_.n + '`') $_.t) + '),0)' }) -join ', '
  Add "SELECT 'ALL', '$t', COUNT(*), $allSums FROM ``$t`` x $where;"
  $actParts = foreach ($c in $cols) {
    $cc = 'x.`' + $c.n + '`'
    $f = Filled $cc $c.t
    $r = Real $cc $c.t
    $rec = if ($hasCreated) { "COALESCE(SUM($f AND x.created_at >= '$Recent'),0)" } else { '0' }
    "COALESCE(SUM($f),0), COALESCE(SUM($r),0), $rec"
  }
  $recN = if ($hasCreated) { "COALESCE(SUM(x.created_at >= '$Recent'),0)" } else { '0' }
  Add "SELECT 'ACT', '$t', COUNT(*), $recN, $($actParts -join ', ') FROM field_usage.a_$t x;"
  foreach ($c in $cols) {
    if ($c.n -eq 'id') { continue }
    $cc = 'x.`' + $c.n + '`'
    $f = Filled $cc $c.t
    if ($c.t -match 'text|json|blob') { $grp = "MD5($cc)"; $val = "ANY_VALUE(LEFT(CAST($cc AS CHAR),60))" }
    else { $grp = $cc; $val = "LEFT(CAST($cc AS CHAR),60)" }
    Add "SELECT 'TOP', '$t', '$($c.n)', COUNT(*), COALESCE(MAX(k),0), SUBSTRING_INDEX(GROUP_CONCAT(REPLACE(REPLACE(v,CHAR(9),' '),CHAR(10),' ') ORDER BY k DESC SEPARATOR '|~|'), '|~|', 1) FROM (SELECT $val v, COUNT(*) k FROM field_usage.a_$t x WHERE $f GROUP BY $grp) g;"
  }
  $seg = $tables[$t].seg
  if ($seg) {
    $segParts = foreach ($c in $cols) {
      $cc = 'x.`' + $c.n + '`'
      "COALESCE(SUM($(Filled $cc $c.t)),0), COALESCE(SUM($(Real $cc $c.t)),0)"
    }
    Add "SELECT 'SEG', '$t', '$seg', CAST(x.``$seg`` AS CHAR), COUNT(*), $($segParts -join ', ') FROM field_usage.a_$t x GROUP BY x.``$seg``;"
  }
}

# --- JSON explode: field_usage.j (tbl, col, seg, k, v, f, r)
$jf = "(v IS NOT NULL AND TRIM(v) NOT IN ('','0','-','--','.','null','NULL','[]','{}','[null]','0000-00-00','00:00','00:00:00','0.0','0.00'))"
Add "CREATE TABLE field_usage.j (tbl VARCHAR(40), col VARCHAR(60), seg VARCHAR(20), k VARCHAR(80), v VARCHAR(191), f TINYINT, r TINYINT) ENGINE=MyISAM;"
foreach ($jc in $jsonCols) {
  $t = $jc[0]; $c = $jc[1]; $kind = $jc[2]
  $seg = $tables[$t].seg
  $segExpr = if ($seg) { "CAST(x.``$seg`` AS CHAR)" } else { "''" }
  $src = "IF(JSON_VALID(x.``$c``), x.``$c``, '[]')"
  # array length distribution (valid JSON only; non-arrays -> -1)
  Add "SELECT 'JLEN', '$t', '$c', CASE WHEN x.``$c`` IS NULL OR TRIM(x.``$c``) = '' THEN 'empty' WHEN NOT JSON_VALID(x.``$c``) THEN 'invalid' WHEN JSON_TYPE(x.``$c``) <> 'ARRAY' THEN 'notarray' ELSE CAST(LEAST(JSON_LENGTH(x.``$c``), 10) AS CHAR) END len, COUNT(*) FROM field_usage.a_$t x GROUP BY len;"
  if ($kind -eq 'scal') {
    Add "INSERT INTO field_usage.j SELECT '$t', '$c', $segExpr, '[]', LEFT(e.v,191), 0, 0 FROM field_usage.a_$t x, JSON_TABLE(CASE WHEN JSON_TYPE($src) = 'ARRAY' THEN $src ELSE '[]' END, '`$[*]' COLUMNS(v VARCHAR(400) PATH '`$')) e;"
  } else {
    Add "INSERT INTO field_usage.j SELECT '$t', '$c', $segExpr, k.k, LEFT(JSON_UNQUOTE(JSON_EXTRACT(e.o, CONCAT('`$.`"', k.k, '`"'))),191), 0, 0 FROM field_usage.a_$t x, JSON_TABLE(CASE WHEN JSON_TYPE($src) = 'ARRAY' THEN $src ELSE '[]' END, '`$[*]' COLUMNS(o JSON PATH '`$')) e, JSON_TABLE(COALESCE(JSON_KEYS(e.o), '[]'), '`$[*]' COLUMNS(k VARCHAR(80) PATH '`$')) k;"
  }
  if ($kind -eq 'days') {
    # nested fuel lines: work_days[].fuels is a JSON-encoded string (sometimes a real array)
    $inner = "CASE WHEN JSON_TYPE(d.fu) = 'ARRAY' THEN CAST(d.fu AS CHAR) WHEN JSON_TYPE(d.fu) = 'STRING' AND JSON_VALID(JSON_UNQUOTE(d.fu)) AND JSON_TYPE(JSON_UNQUOTE(d.fu)) = 'ARRAY' THEN JSON_UNQUOTE(d.fu) ELSE '[]' END"
    Add "INSERT INTO field_usage.j SELECT '$t', '$c.fuels', $segExpr, k.k, LEFT(JSON_UNQUOTE(JSON_EXTRACT(e.o, CONCAT('`$.`"', k.k, '`"'))),191), 0, 0 FROM field_usage.a_$t x, JSON_TABLE(CASE WHEN JSON_TYPE($src) = 'ARRAY' THEN $src ELSE '[]' END, '`$[*]' COLUMNS(fu JSON PATH '`$.fuels')) d, JSON_TABLE($inner, '`$[*]' COLUMNS(o JSON PATH '`$')) e, JSON_TABLE(COALESCE(JSON_KEYS(e.o), '[]'), '`$[*]' COLUMNS(k VARCHAR(80) PATH '`$')) k;"
    Add "SELECT 'JLEN', '$t', '$c.days_with_fuels', 'n', COUNT(*) FROM field_usage.a_$t x WHERE x.``$c`` LIKE '%fuel_given%';"
  }
}
Add "UPDATE field_usage.j SET f = $jf;"
Add "UPDATE field_usage.j SET r = (f = 1 AND TRIM(v) <> '1');"
Add "SELECT 'JSON', tbl, col, '*', k, COUNT(*), SUM(f), SUM(r), COUNT(DISTINCT CASE WHEN f = 1 THEN v END) FROM field_usage.j GROUP BY tbl, col, k;"
Add "SELECT 'JSEG', tbl, col, seg, k, COUNT(*), SUM(f), SUM(r) FROM field_usage.j WHERE seg <> '' GROUP BY tbl, col, seg, k;"
Add "SELECT 'JTOP', tbl, col, '*', k, v, c FROM (SELECT tbl, col, k, REPLACE(REPLACE(LEFT(v,60),CHAR(9),' '),CHAR(10),' ') v, COUNT(*) c, ROW_NUMBER() OVER (PARTITION BY tbl, col, k ORDER BY COUNT(*) DESC) rn FROM field_usage.j WHERE f = 1 GROUP BY tbl, col, k, v) z WHERE rn <= 3;"
Add "DROP DATABASE field_usage;"

$f = Join-Path $out 'usage-all.sql'
[IO.File]::WriteAllText($f, $sql.ToString(), (New-Object System.Text.UTF8Encoding($false)))
if ($DryRun) { "dry run: $f"; return }
docker cp $f "${Db}:/tmp/usage-all.sql" | Out-Null
$t0 = Get-Date
# --force: one failing statement must not stop the whole run (errors -> usage-all.err)
docker exec $Db sh -c "mysql --force -uroot -psecret --default-character-set=utf8mb4 rohkhat -N < /tmp/usage-all.sql > /tmp/usage-all.tsv 2>/tmp/usage-all.err"
docker cp "${Db}:/tmp/usage-all.tsv" (Join-Path $out 'usage-all.tsv') | Out-Null
docker exec $Db sh -c "grep -v Warning /tmp/usage-all.err | head -20"
"sql run: {0:N0} s" -f ((Get-Date) - $t0).TotalSeconds

# ---------------------------------------------------------------- post-processing -> CSV
$lines = [IO.File]::ReadAllLines((Join-Path $out 'usage-all.tsv'), [Text.Encoding]::UTF8)
$colsBy = @{}
foreach ($t in $tables.Keys) { $colsBy[$t] = @(Cols $t) }
$all = @{}; $act = @{}; $top = @{}; $rows = New-Object System.Collections.Generic.List[string]
foreach ($ln in $lines) {
  $p = $ln -split "`t"
  switch ($p[0]) {
    'ALL' { $all[$p[1]] = $p }
    'ACT' { $act[$p[1]] = $p }
    'TOP' { $top[$p[1] + '.' + $p[2]] = $p }
  }
}
function Pct($a, $b) { if ([double]$b -gt 0) { '{0:N1}' -f (100.0 * [double]$a / [double]$b) } else { '' } }
$rows.Add('table;column;type;total;filled_all_pct;active;filled_act;filled_act_pct;real_act_pct;recent_act;filled_recent_pct;distinct;top_share_pct;top_value')
foreach ($t in $tables.Keys) {
  if (-not $all.ContainsKey($t) -or -not $act.ContainsKey($t)) { continue }
  $a = $all[$t]; $x = $act[$t]
  $total = [double]$a[2]; $nAct = [double]$x[2]; $nRec = [double]$x[3]
  $i = 0
  foreach ($c in $colsBy[$t]) {
    $fa = $a[3 + $i]; $fx = $x[4 + 3 * $i]; $rx = $x[5 + 3 * $i]; $fr = $x[6 + 3 * $i]
    $tp = $top["$t.$($c.n)"]
    $nd = if ($tp) { $tp[3] } else { '' }
    $tk = if ($tp) { $tp[4] } else { '0' }
    $tv = if ($tp -and $tp.Count -gt 5) { ($tp[5] -replace ';', ',') } else { '' }
    $rows.Add(('{0};{1};{2};{3};{4};{5};{6};{7};{8};{9};{10};{11};{12};{13}' -f $t, $c.n, $c.t, $total, (Pct $fa $total), $nAct, $fx, (Pct $fx $nAct), (Pct $rx $nAct), $nRec, (Pct $fr $nRec), $nd, (Pct $tk $fx), $tv))
    $i++
  }
}
[IO.File]::WriteAllLines((Join-Path $out 'usage-all.csv'), $rows, (New-Object System.Text.UTF8Encoding($true)))
"tsv rows: {0}; csv rows: {1}; out: {2}" -f $lines.Count, $rows.Count, $out
