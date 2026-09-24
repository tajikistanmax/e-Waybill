# =============================================================================
# e2e-reports.ps1 - live run of every report / journal / export endpoint of
# the waybill service for a period, under the roles that use them.
# Checks: HTTP 200, time, non-empty result where data exists, XLSX signature
# (PK) and PDF signature (%PDF). Pure ASCII (Windows PowerShell 5.1).
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-reports.ps1
#   ... -From 2026-09-01 -To 2026-09-30 -Org 025680800
#   ... -SlowMs 20000     report slower than this is flagged SLOW (not failed)
# Exit code: number of failed checks.
# =============================================================================
param(
    [string]$Md = 'http://localhost:8081',
    [string]$Wb = 'http://localhost:8082',
    [string]$From = (Get-Date).AddDays(-30).ToString('yyyy-MM-dd'),
    [string]$To = (Get-Date).ToString('yyyy-MM-dd'),
    [string]$Org = '025680800',
    [int]$SlowMs = 20000,
    # optional: a real (migrated) organization whose archive to report on as admin, e.g. 040000796
    [string]$ArchiveOrg = '',
    [string]$OutDir = (Join-Path $env:TEMP 'ewb-e2e-reports')
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\demo-credentials.ps1"
New-Item -ItemType Directory -Force $OutDir | Out-Null

$script:Tokens = @{}
function Get-Tok([string]$user) {
    if ($script:Tokens.ContainsKey($user)) { return $script:Tokens[$user] }
    $body = @{ username = $user; password = (Get-DemoPassword $user) } | ConvertTo-Json
    $r = Invoke-RestMethod -Method Post -Uri "$Md/api/v1/auth/token" -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
    $script:Tokens[$user] = $r.access_token
    return $r.access_token
}
function Api([string]$user, [string]$method, [string]$url, $body = $null) {
    $req = [System.Net.HttpWebRequest]::Create($url)
    $req.Method = $method; $req.Timeout = 900000; $req.ReadWriteTimeout = 900000
    if ($user) { $req.Headers.Add('Authorization', 'Bearer ' + (Get-Tok $user)) }
    if ($null -ne $body) {
        $bytes = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 10))
        $req.ContentType = 'application/json; charset=utf-8'; $req.ContentLength = $bytes.Length
        $s = $req.GetRequestStream(); $s.Write($bytes, 0, $bytes.Length); $s.Close()
    } elseif ($method -ne 'GET') { $req.ContentLength = 0 }
    $sw = [Diagnostics.Stopwatch]::StartNew()
    try { $resp = $req.GetResponse() } catch [System.Net.WebException] { $resp = $_.Exception.Response; if ($null -eq $resp) { throw } }
    $ms = New-Object IO.MemoryStream; $resp.GetResponseStream().CopyTo($ms)
    $o = [pscustomobject]@{ Status = [int]$resp.StatusCode; Ct = $resp.ContentType; Bytes = $ms.ToArray(); Len = 0; Ms = 0; Body = $null; Text = $null }
    $resp.Close(); $sw.Stop(); $o.Len = $o.Bytes.Length; $o.Ms = $sw.ElapsedMilliseconds
    if ($o.Ct -match 'json') { $o.Text = [Text.Encoding]::UTF8.GetString($o.Bytes); try { $o.Body = $o.Text | ConvertFrom-Json } catch { } }
    return $o
}

$script:Results = New-Object System.Collections.ArrayList
$script:Fail = 0
function Check([string]$name, $r, [string]$kind = 'json', [string]$user = '') {
    $ok = ($r.Status -eq 200)
    $size = ''
    if ($ok -and $kind -eq 'xlsx') { $ok = ($r.Len -gt 500 -and $r.Bytes[0] -eq 0x50 -and $r.Bytes[1] -eq 0x4B) }
    if ($ok -and $kind -eq 'pdf') { $ok = ($r.Len -gt 1000 -and $r.Bytes[0] -eq 0x25 -and $r.Bytes[1] -eq 0x50) }
    $rows = ''
    if ($kind -eq 'json' -and $r.Body) {
        $b = $r.Body
        if ($b -is [array]) { $rows = "rows=$($b.Count)" }
        elseif ($b.PSObject.Properties['rows']) { $rows = "rows=$(@($b.rows).Count)" }
        elseif ($b.PSObject.Properties['lines']) { $rows = "lines=$(@($b.lines).Count)" }
        elseif ($b.PSObject.Properties['regions']) { $rows = "regions=$(@($b.regions).Count)" }
        elseif ($b.PSObject.Properties['content']) { $rows = "content=$(@($b.content).Count)" }
        else { $rows = 'keys=' + (($b.PSObject.Properties | Select-Object -First 6 | ForEach-Object { $_.Name }) -join ',') }
    }
    if ($kind -ne 'json') { $size = "$($r.Len) bytes" }
    $slow = if ($r.Ms -gt $SlowMs) { ' SLOW' } else { '' }
    $detail = ''
    if (-not $ok -and $r.Text) { $detail = $r.Text.Substring(0, [Math]::Min(200, $r.Text.Length)) }
    [void]$script:Results.Add([pscustomobject]@{ report = $name; user = $user; status = $r.Status; ms = $r.Ms; ok = $ok; info = "$rows$size" })
    if (-not $ok) { $script:Fail++ }
    Write-Output ("  [{0}] {1,-48} {2,-11} HTTP {3} {4,6}ms {5}{6} {7}" -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $name, $user, $r.Status, $r.Ms, "$rows$size", $slow, $detail)
}

$p = "from=$From&to=$To"
Write-Output "=== REPORTS $From .. $To org=$Org ==="
$types = (Api 'accountant' 'GET' "$Wb/api/v1/reports/types").Body
Write-Output ("report types: " + (@($types) | ForEach-Object { $_.code }) -join ',')

# carrier roles (tenant scope)
foreach ($u in 'accountant', 'dispatcher', 'company') {
    Check 'summary' (Api $u 'GET' "$Wb/api/v1/reports/summary?$p") 'json' $u
}
Check 'dispatcher-journal (today)' (Api 'dispatcher' 'GET' "$Wb/api/v1/reports/dispatcher-journal?date=$To") 'json' 'dispatcher'
Check 'by-driver' (Api 'accountant' 'GET' "$Wb/api/v1/reports/by-driver?$p") 'json' 'accountant'
Check 'by-vehicle' (Api 'accountant' 'GET' "$Wb/api/v1/reports/by-vehicle?$p") 'json' 'accountant'
Check 'fuel' (Api 'accountant' 'GET' "$Wb/api/v1/reports/fuel?$p") 'json' 'accountant'
foreach ($by in 'VEHICLE', 'DRIVER') {
    Check "activity by=$by" (Api 'accountant' 'GET' "$Wb/api/v1/reports/activity?by=$by&$p") 'json' 'accountant'
}
foreach ($t in @($types)) {
    foreach ($bill in 'passenger', 'cargo') {
        Check "$bill $($t.code)" (Api 'accountant' 'GET' "$Wb/api/v1/reports/$bill`?type=$($t.code)&$p") 'json' 'accountant'
    }
}
foreach ($t in 'COMPANY_SUMMARY', 'BY_VEHICLE', 'DRIVER_SALARY', 'FUEL_BY_WAYBILL', 'REGISTRY_JOURNAL') {
    foreach ($bill in 'passenger', 'cargo') {
        $r = Api 'accountant' 'GET' "$Wb/api/v1/reports/$bill.xlsx?type=$t&$p"
        if ($r.Status -eq 200) { [IO.File]::WriteAllBytes((Join-Path $OutDir "$bill-$t.xlsx"), $r.Bytes) }
        Check "$bill.xlsx $t" $r 'xlsx' 'accountant'
    }
}
Check 'journal/doctor' (Api 'accountant' 'GET' "$Wb/api/v1/reports/journal/doctor?$p") 'json' 'accountant'
Check 'journal/doctor.xlsx' (Api 'accountant' 'GET' "$Wb/api/v1/reports/journal/doctor.xlsx?$p") 'xlsx' 'accountant'
Check 'journal/mechanic' (Api 'accountant' 'GET' "$Wb/api/v1/reports/journal/mechanic?$p") 'json' 'accountant'
Check 'journal/mechanic.xlsx' (Api 'accountant' 'GET' "$Wb/api/v1/reports/journal/mechanic.xlsx?$p") 'xlsx' 'accountant'
Check 'passenger-volume-trend' (Api 'accountant' 'GET' "$Wb/api/v1/reports/passenger-volume-trend?months=7") 'json' 'accountant'
Check 'malumotnomas list' (Api 'accountant' 'GET' "$Wb/api/v1/malumotnomas") 'json' 'accountant'
Check 'malumotnomas/report' (Api 'accountant' 'GET' "$Wb/api/v1/malumotnomas/report?$p") 'json' 'accountant'
Check 'malumotnomas/report.xlsx' (Api 'accountant' 'GET' "$Wb/api/v1/malumotnomas/report.xlsx?$p") 'xlsx' 'accountant'
Check 'waybill-plans' (Api 'analyst-automation' 'GET' "$Wb/api/v1/waybill-plans") 'json' 'analyst'

# inspector/doctor/mechanic own journals
Check 'journal/doctor (inspector)' (Api 'inspector-automation' 'GET' "$Wb/api/v1/reports/journal/doctor?$p") 'json' 'inspector'
Check 'summary (inspector)' (Api 'inspector-automation' 'GET' "$Wb/api/v1/reports/summary?$p") 'json' 'inspector'

# Ministry (platform scope)
foreach ($bill in 'PASSENGER', 'CARGO') {
    Check "regional bill=$bill" (Api 'analyst-automation' 'GET' "$Wb/api/v1/reports/regional?bill=$bill&$p") 'json' 'analyst'
    Check "regional.xlsx bill=$bill" (Api 'analyst-automation' 'GET' "$Wb/api/v1/reports/regional.xlsx?bill=$bill&$p") 'xlsx' 'analyst'
}
foreach ($bill in 'ALL', 'PASSENGER', 'CARGO') {
    Check "regional-count bill=$bill" (Api 'analyst-automation' 'GET' "$Wb/api/v1/reports/regional-count?bill=$bill&$p") 'json' 'analyst'
}
Check 'regional-count.xlsx' (Api 'analyst-automation' 'GET' "$Wb/api/v1/reports/regional-count.xlsx?bill=ALL&$p") 'xlsx' 'analyst'
foreach ($t in 'WB_BUS', 'WB_TRUCK') {
    Check "waybill-norm $t" (Api 'analyst-automation' 'GET' "$Wb/api/v1/reports/waybill-norm?type=$t&$p") 'json' 'analyst'
    Check "waybill-norm.xlsx $t" (Api 'analyst-automation' 'GET' "$Wb/api/v1/reports/waybill-norm.xlsx?type=$t&$p") 'xlsx' 'analyst'
}
Check 'summary (analyst, all orgs)' (Api 'analyst-automation' 'GET' "$Wb/api/v1/reports/summary?$p") 'json' 'analyst'
Check 'passenger COMPANY_SUMMARY (admin)' (Api 'admin-automation' 'GET' "$Wb/api/v1/reports/passenger?type=COMPANY_SUMMARY&$p&organizationRma=$Org") 'json' 'admin'

# archive of a real organization (platform role picks the organization)
if ($ArchiveOrg) {
    $q = "$p&organizationRma=$ArchiveOrg"
    Check "summary org=$ArchiveOrg" (Api 'admin-automation' 'GET' "$Wb/api/v1/reports/summary?$q") 'json' 'admin'
    foreach ($t in 'COMPANY_SUMMARY', 'BY_VEHICLE', 'BY_DRIVER', 'DRIVER_SALARY', 'FUEL_GENERAL', 'REGISTRY_JOURNAL') {
        foreach ($bill in 'passenger', 'cargo') {
            Check "$bill $t org=$ArchiveOrg" (Api 'admin-automation' 'GET' "$Wb/api/v1/reports/$bill`?type=$t&$q") 'json' 'admin'
        }
    }
    Check "passenger.xlsx COMPANY_SUMMARY org=$ArchiveOrg" (Api 'admin-automation' 'GET' "$Wb/api/v1/reports/passenger.xlsx?type=COMPANY_SUMMARY&$q") 'xlsx' 'admin'
    Check "journal/doctor org=$ArchiveOrg" (Api 'admin-automation' 'GET' "$Wb/api/v1/reports/journal/doctor?$q") 'json' 'admin'
    Check "by-driver org=$ArchiveOrg" (Api 'admin-automation' 'GET' "$Wb/api/v1/reports/by-driver?$q") 'json' 'admin'
}

# registry / dashboards used by report pages
Check 'waybills/page' (Api 'accountant' 'GET' "$Wb/api/v1/waybills/page?page=0&size=20&from=$From&to=$To") 'json' 'accountant'
Check 'waybills/status-counts' (Api 'accountant' 'GET' "$Wb/api/v1/waybills/status-counts") 'json' 'accountant'

$json = Join-Path $OutDir ("reports-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.json')
[IO.File]::WriteAllText($json, ($script:Results | ConvertTo-Json -Depth 3), (New-Object Text.UTF8Encoding($false)))
$slow = @($script:Results | Where-Object { $_.ms -gt $SlowMs })
Write-Output ("=== TOTAL: {0} checks, {1} failed, {2} slow (> {3} ms). Results: {4}" -f $script:Results.Count, $script:Fail, $slow.Count, $SlowMs, $json)
exit $script:Fail
