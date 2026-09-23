<#
  seed-demo-showcase.ps1 - repeatable DEMO data set for the customer show.

  What it does (all through the public API, no direct SQL):
    1. Organizations "(demo)" from scripts/demo-showcase.json: a city bus company with
       2 branches, a taxi company, a cargo carrier, an intercity/international passenger
       carrier and an international cargo (CMR) carrier.
    2. Their staff (doctor / mechanic / dispatcher / fuel station / cashier-accountant),
       drivers, vehicles, routes and clients (upsert = safe to repeat).
    3. Logins for every demo role with ONE known permanent password (from the JSON);
       temporary passwords are changed through /api/v1/auth/password automatically.
    4. Waybills of every enabled form in every lifecycle status for TODAY:
       draft, waiting for medical check, waiting for tech check, waiting for payment,
       ready, issued, on the line, returned, closed (with calculation), cancelled,
       blocked by an inspector, rejected by doctor / mechanic.
       Each waybill carries typeData.demoKey = "<yyyy-MM-dd>|<scenario key>":
         - a second run on the same day finds the same waybills and only finishes the
           ones that are not yet in the target status (no duplicates);
         - a run on a NEW day first retires the open demo waybills of previous days
           (on-line ones are returned and closed, unfinished ones are cancelled), so the
           vehicles are free, and then builds the fresh set dated today - the "today"
           reports are full again.
    5. Checks every demo login (sign-in + what the cabinet sees) and prints the table
       of logins and of the waybills created.

  Usage (local stand, ports 8081/8082 published by docker-compose.local.yml):
      powershell -ExecutionPolicy Bypass -File scripts\seed-demo-showcase.ps1
  Options:
      -MdUrl / -WbUrl          API addresses (default http://localhost:8081 / :8082)
      -AdminPassword           password of the platform admin (default: scripts\demo-credentials.ps1)
      -InspectorPassword       password of the inspector login (default: same file)
      -SkipWaybills            only organizations, staff and logins
      -SkipVerify              do not re-check the logins at the end

  The file is ASCII on purpose (Windows PowerShell 5.1 misreads UTF-8 scripts);
  all Russian text lives in demo-showcase.json and is read as UTF-8.
#>
[CmdletBinding()]
param(
    [string]$DataFile = '',
    [string]$MdUrl = 'http://localhost:8081',
    [string]$WbUrl = 'http://localhost:8082',
    [string]$AdminUser = 'admin',
    [string]$AdminPassword = '',
    [string]$InspectorUser = 'inspector',
    [string]$InspectorPassword = '',
    [switch]$SkipWaybills,
    [switch]$SkipVerify,
    # Testing aids: pretend today is another day (yyyy-MM-dd) / only retire other days' waybills.
    [string]$DemoDay = '',
    [switch]$RetireOnly
)

$ErrorActionPreference = 'Stop'
# Windows PowerShell 5.1 does not fill $PSScriptRoot inside param() defaults - resolve here.
$ScriptDir = $PSScriptRoot
if (-not $ScriptDir) { $ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not $DataFile) { $DataFile = Join-Path $ScriptDir 'demo-showcase.json' }
$credFile = Join-Path $ScriptDir 'demo-credentials.ps1'
if (Test-Path $credFile) { . $credFile }
if (-not $AdminPassword -and (Get-Command Get-DemoPassword -ErrorAction SilentlyContinue)) { $AdminPassword = Get-DemoPassword $AdminUser }
if (-not $InspectorPassword -and (Get-Command Get-DemoPassword -ErrorAction SilentlyContinue)) { $InspectorPassword = Get-DemoPassword $InspectorUser }

$Data = Get-Content -Raw -Encoding UTF8 -Path $DataFile | ConvertFrom-Json
$Password = [string]$Data.password
$Text = $Data.text
$Today = Get-Date
$Day = $Today.ToString('yyyy-MM-dd')
if ($DemoDay) { $Day = $DemoDay }

$Passenger = @('WB_BUS', 'WB_TROLLEYBUS', 'WB_MINIBUS', 'WB_TAXI', 'WB_PAX_INTL')
$IntlTypes = @('WB_TRUCK_INTL', 'WB_PAX_INTL')
$MaxDays = @{ WB_CAR = 7; WB_TAXI = 7; WB_MINIBUS = 4; WB_BUS = 1; WB_TROLLEYBUS = 1; WB_TRUCK = 15; WB_TRUCK_INTL = 30; WB_PAX_INTL = 30; WB_SPECIAL = 7; WB_DANGEROUS = 1 }
$RoleByEmployeeType = @{ 1 = 'DOCTOR'; 2 = 'MECHANIC'; 3 = 'DISPATCHER'; 4 = 'FUEL_STATION'; 5 = 'ACCOUNTANT' }
$SlotByEmployeeType = @{ 1 = 'doctor'; 2 = 'mech'; 3 = 'disp'; 4 = 'azs'; 5 = 'buh' }

$script:Problems = New-Object System.Collections.ArrayList
$script:LoginRows = New-Object System.Collections.ArrayList
$script:WaybillRows = New-Object System.Collections.ArrayList
$script:Tokens = @{}

function Add-Problem([string]$msg) { [void]$script:Problems.Add($msg); Write-Host "  [!] $msg" -ForegroundColor Yellow }
function D([int]$days) { $Today.AddDays($days).ToString('yyyy-MM-dd') }

# ------------------------------------------------------------------ HTTP

function Invoke-Api {
    param([string]$Method, [string]$Url, [string]$Token, $Body, [switch]$AllowError)
    $headers = @{}
    if ($Token) { $headers['Authorization'] = "Bearer $Token" }
    $req = @{ Method = $Method; Uri = $Url; Headers = $headers; UseBasicParsing = $true }
    if ($null -ne $Body) {
        $json = if ($Body -is [string]) { $Body } else { ConvertTo-Json -InputObject $Body -Depth 20 }
        $req['Body'] = [Text.Encoding]::UTF8.GetBytes($json)
        $req['ContentType'] = 'application/json; charset=utf-8'
    }
    for ($attempt = 1; $attempt -le 8; $attempt++) {
        try {
            $r = Invoke-WebRequest @req
            $txt = [Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
            $obj = $null
            if ($txt -and $txt.Trim()) { $obj = ConvertFrom-Json -InputObject $txt }
            return [pscustomobject]@{ Status = [int]$r.StatusCode; Data = $obj; Text = $txt }
        } catch {
            $resp = $_.Exception.Response
            if (-not $resp) { throw }
            $status = [int]$resp.StatusCode
            $txt = ''
            try {
                $sr = New-Object IO.StreamReader($resp.GetResponseStream(), [Text.Encoding]::UTF8)
                $txt = $sr.ReadToEnd()
            } catch { }
            if (-not $txt -and $_.ErrorDetails) { $txt = $_.ErrorDetails.Message }
            # 429 = our own request rate; 503 + retryAfterSeconds = waybill-service was throttled by
            # master-data on our behalf. Both are temporary: wait and repeat the same call.
            $throttled = ($status -eq 429 -and $txt -match 'too_many_requests') -or ($status -eq 503 -and $txt -match 'retryAfterSeconds')
            if ($throttled -and $attempt -lt 8) {
                $wait = 10
                try { $ra = $resp.Headers['Retry-After']; if ($ra) { $wait = [int]$ra + 1 } } catch { }
                try { $pj = ConvertFrom-Json -InputObject $txt; if ($pj.retryAfterSeconds) { $wait = [int]$pj.retryAfterSeconds + 1 } } catch { }
                Write-Host "    (rate limit, waiting $wait s)"
                Start-Sleep -Seconds $wait
                continue
            }
            $obj = $null
            try { if ($txt) { $obj = ConvertFrom-Json -InputObject $txt } } catch { }
            $res = [pscustomobject]@{ Status = $status; Data = $obj; Text = $txt }
            if ($AllowError) { return $res }
            $msg = $txt
            if ($obj -and $obj.message) { $msg = $obj.message } elseif ($obj -and $obj.detail) { $msg = $obj.detail }
            throw "HTTP $status $Method $Url : $msg"
        }
    }
}

function Get-Login([string]$user, [string]$pass) {
    Invoke-Api -Method POST -Url "$MdUrl/api/v1/auth/token" -Body @{ username = $user; password = $pass } -AllowError
}

function ConvertTo-Plain($o) {
    if ($null -eq $o) { return $null }
    if ($o -is [System.Management.Automation.PSCustomObject]) {
        $h = [ordered]@{}
        foreach ($p in $o.PSObject.Properties) { $h[$p.Name] = ConvertTo-Plain $p.Value }
        return $h
    }
    if (($o -is [System.Collections.IEnumerable]) -and -not ($o -is [string])) {
        $list = New-Object System.Collections.ArrayList
        foreach ($i in $o) { [void]$list.Add((ConvertTo-Plain $i)) }
        return , $list.ToArray()
    }
    return $o
}

function Get-Prop($obj, [string]$name) {
    if ($null -eq $obj) { return $null }
    $p = $obj.PSObject.Properties[$name]
    if ($p) { return $p.Value }
    return $null
}

# ------------------------------------------------------------------ logins

$AdminTok = $null
$r = Get-Login $AdminUser $AdminPassword
if ($r.Status -ne 200) { throw "Cannot sign in as platform admin '$AdminUser' (HTTP $($r.Status)). Pass -AdminPassword." }
$AdminTok = $r.Data.access_token
$InspectorTok = $null
$r = Get-Login $InspectorUser $InspectorPassword
if ($r.Status -eq 200) { $InspectorTok = $r.Data.access_token } else { Add-Problem "inspector login '$InspectorUser' failed (HTTP $($r.Status)); blocked-by-inspector waybills will be skipped" }

# Makes sure the login exists with the given role and ends up with the permanent demo password.
function Set-DemoUser {
    param([string]$Login, [string]$Role, [string]$OrgRma, [string]$PersonRma, [string]$First, [string]$Last, $ClientIds, $Existing)
    $user = $null
    foreach ($e in @($Existing)) { if ($e -and $e.username -eq $Login) { $user = $e } }
    $tmp = 'Tmp-' + $Password + '-1'
    if (-not $user) {
        $body = [ordered]@{ username = $Login; firstName = $First; lastName = $Last; organizationRma = $OrgRma; role = $Role; password = $tmp }
        if ($PersonRma) { $body['personRma'] = $PersonRma }
        if ($ClientIds) { $body['clientIds'] = @($ClientIds) }
        $c = Invoke-Api -Method POST -Url "$MdUrl/api/v1/org-users" -Token $AdminTok -Body $body -AllowError
        if ($c.Status -ne 201 -and $c.Status -ne 200) {
            Add-Problem "login $Login not created: HTTP $($c.Status) $($c.Text)"
            return $null
        }
        $user = $c.Data
    } else {
        $roles = @($user.roles)
        if ($roles -notcontains $Role) {
            [void](Invoke-Api -Method PATCH -Url "$MdUrl/api/v1/org-users/$($user.id)/role" -Token $AdminTok -Body @{ username = $Login; organizationRma = $OrgRma; role = $Role })
        }
        if ($PersonRma -and $user.rma -and $user.rma -ne $PersonRma) { Add-Problem "login $Login is bound to person $($user.rma), expected $PersonRma" }
        if (-not $user.enabled) {
            [void](Invoke-Api -Method PATCH -Url "$MdUrl/api/v1/org-users/$($user.id)/enabled" -Token $AdminTok -Body @{ enabled = $true })
        }
    }
    # Password: try the permanent one first; otherwise go through the temporary-password flow.
    $l = Get-Login $Login $Password
    if ($l.Status -eq 200) { $script:Tokens[$Login] = $l.Data.access_token; return $user }
    try {
        $null = Set-PermanentPassword -Login $Login -User $user -Tmp $tmp -FirstTry $l
    } catch {
        Add-Problem "login $Login : permanent password not set - $($_.Exception.Message)"
    }
    return $user
}

function Set-PermanentPassword {
    param([string]$Login, $User, [string]$Tmp, $FirstTry)
    $l = $FirstTry
    $user = $User
    $tmp = $Tmp
    $current = $null
    if ($l.Status -eq 428) {
        $current = $Password
    } else {
        $l2 = Get-Login $Login $tmp
        if ($l2.Status -eq 428 -or $l2.Status -eq 200) {
            $current = $tmp
        } else {
            $rp = Invoke-Api -Method POST -Url "$MdUrl/api/v1/org-users/$($user.id)/reset-password" -Token $AdminTok -Body @{}
            $current = $rp.Data.temporaryPassword
        }
    }
    $l = Get-Login $Login $current
    $next = $Password
    if ($current -eq $Password) { $next = $Password + '-x' }
    if ($l.Status -eq 428) {
        [void](Invoke-Api -Method POST -Url "$MdUrl/api/v1/auth/password" -Body @{ changeToken = $l.Data.changeToken; newPassword = $next })
    } elseif ($l.Status -eq 200) {
        [void](Invoke-Api -Method POST -Url "$MdUrl/api/v1/auth/password" -Token $l.Data.access_token -Body @{ currentPassword = $current; newPassword = $next })
    } else {
        Add-Problem "login $Login : cannot set password (HTTP $($l.Status))"
        return $user
    }
    if ($next -ne $Password) {
        $l = Get-Login $Login $next
        [void](Invoke-Api -Method POST -Url "$MdUrl/api/v1/auth/password" -Token $l.Data.access_token -Body @{ currentPassword = $next; newPassword = $Password })
    }
    $l = Get-Login $Login $Password
    if ($l.Status -eq 200) { $script:Tokens[$Login] = $l.Data.access_token } else { Add-Problem "login $Login : permanent password does not work (HTTP $($l.Status))" }
    return $user
}

function Get-UserToken([string]$login) {
    if (-not $login) { return $null }
    if ($script:Tokens.ContainsKey($login)) { return $script:Tokens[$login] }
    $l = Get-Login $login $Password
    if ($l.Status -eq 200) { $script:Tokens[$login] = $l.Data.access_token; return $l.Data.access_token }
    return $null
}

# ------------------------------------------------------------------ master data

$Units = @($Data.units)
$UnitByCode = @{}
foreach ($u in $Units) { $UnitByCode[$u.code] = $u }
$Ctx = @{}   # code -> runtime info (rma, slots, driver logins, client ids)

Write-Host "=== 1. Organizations, staff, drivers, vehicles, routes, clients ===" -ForegroundColor Cyan
foreach ($u in $Units) {
    $org = ConvertTo-Plain $u.org
    $rma = [string]$org['rma']
    if (-not $org.Contains('licenseFrom')) { $org['licenseFrom'] = '2025-01-01' }
    if (-not $org.Contains('licenseTo')) { $org['licenseTo'] = D 730 }
    if ($u.parent) { $org['parentRma'] = [string]$UnitByCode[$u.parent].org.rma }
    [void](Invoke-Api -Method POST -Url "$MdUrl/api/v1/organizations" -Token $AdminTok -Body $org)
    $c = @{ rma = $rma; code = $u.code; slots = @{}; signer = @{}; driverLogin = @{}; clients = @{}; name = [string]$org['name'] }
    $nE = 0; $nD = 0; $nV = 0; $nR = 0
    foreach ($e in @($u.employees)) {
        if (-not $e) { continue }
        $b = [ordered]@{ rma = [string]$e.rma; organizationRma = $rma; name = [string]$e.name; type = [int]$e.type; phone = '+992 37 000-' + ([string]$e.rma).Substring(6) }
        if ([int]$e.type -eq 1) { $b['certNumber'] = [string]$e.certNumber; $b['certValidTo'] = D 365 }
        [void](Invoke-Api -Method POST -Url "$MdUrl/api/v1/employees" -Token $AdminTok -Body $b)
        $slot = $SlotByEmployeeType[[int]$e.type]
        if (-not $c.signer.ContainsKey($slot)) { $c.signer[$slot] = [string]$e.rma }
        if ($e.login -and -not $c.slots.ContainsKey($slot)) { $c.slots[$slot] = [string]$e.login }
        $nE++
    }
    foreach ($d in @($u.drivers)) {
        if (-not $d) { continue }
        $b = [ordered]@{
            rma = [string]$d.rma; organizationRma = $rma; fullName = [string]$d.fullName
            birthDate = [string]$d.birthDate; experienceYears = [int]$d.experienceYears; degree = [int]$d.degree
            licenseNumber = 'DL' + ([string]$d.rma).Substring(4); licenseCategories = [string]$d.cats; licenseValidTo = D 1095
            medCertNumber = 'MED-' + ([string]$d.rma).Substring(4); medCertValidTo = D 300
            safetyCourseNumber = 'BDD-' + ([string]$d.rma).Substring(4); safetyCourseValidTo = D 365
            passport = 'A' + ([string]$d.rma).Substring(3)
        }
        if ($d.phone) { $b['phone'] = [string]$d.phone }
        if ($d.adr) { $b['adrCertValidTo'] = D 365 }
        if ($d.visa) { $b['visaValidTo'] = D 180 }
        [void](Invoke-Api -Method POST -Url "$MdUrl/api/v1/drivers" -Token $AdminTok -Body $b)
        if ($d.login) { $c.driverLogin[[string]$d.rma] = [string]$d.login }
        $nD++
    }
    foreach ($v in @($u.vehicles)) {
        if (-not $v) { continue }
        $reg = [string]$v.reg
        $exists = (Invoke-Api -Method GET -Url "$MdUrl/api/v1/vehicles?registrationNumber=$reg" -Token $AdminTok).Data
        $isNew = -not (@($exists) | Where-Object { $_ -and $_.registrationNumber -eq $reg })
        $b = [ordered]@{
            registrationNumber = $reg; organizationRma = $rma; transportType = [int]$v.type; brand = [string]$v.brand
            fuelType = [int]$v.fuelType; yearManufacture = [int]$v.year
            techInspectionNumber = 'TO-' + $reg; techInspectionValidTo = D 200
            controlCardNumber = 'KK-' + $reg; controlCardValidTo = D 365
            insuranceValidTo = D 300; techPassportNumber = 'TP-' + $reg
        }
        if ($null -ne $v.capacity) { $b['capacity'] = [int]$v.capacity }
        if ($null -ne $v.carrying) { $b['carrying'] = $v.carrying }
        if ($null -ne $v.airConditioner) { $b['airConditioner'] = [int]$v.airConditioner }
        if ($v.adr) { $b['adrApprovalValidTo'] = D 365 }
        if ($v.intl) { $b['intlCertificateNumber'] = 'MS-' + $reg; $b['intlControlCardNumber'] = 'MKK-' + $reg; $b['intlControlCardValidTo'] = D 365 }
        if ($v.trailer1Number) { $b['trailer1Number'] = [string]$v.trailer1Number; $b['trailer1Brand'] = [string]$v.trailer1Brand; $b['trailer1Carrying'] = $v.trailer1Carrying }
        if ($isNew -and $null -ne $v.odometer) { $b['odometer'] = [int]$v.odometer }
        $vr = Invoke-Api -Method POST -Url "$MdUrl/api/v1/vehicles" -Token $AdminTok -Body $b -AllowError
        if ($vr.Status -ge 300) { Add-Problem "vehicle $reg : HTTP $($vr.Status) $($vr.Text)" } else { $nV++ }
    }
    foreach ($rt in @($u.routes)) {
        if (-not $rt) { continue }
        $b = ConvertTo-Plain $rt
        $b['organizationRma'] = $rma
        [void](Invoke-Api -Method POST -Url "$MdUrl/api/v1/dictionaries/routes" -Token $AdminTok -Body $b)
        $nR++
    }
    foreach ($cl in @($u.clients)) {
        if (-not $cl) { continue }
        $b = ConvertTo-Plain $cl
        $b['organizationRma'] = $rma
        $cr = Invoke-Api -Method POST -Url "$MdUrl/api/v1/dictionaries/clients" -Token $AdminTok -Body $b
        $c.clients[[string]$cl.number] = @{ id = [string]$cr.Data.id; name = [string]$cl.name; address = [string]$cl.address }
    }
    $Ctx[$u.code] = $c
    Write-Host ("  {0,-10} {1}  staff={2} drivers={3} vehicles={4} routes={5} clients={6}" -f $u.code, $rma, $nE, $nD, $nV, $nR, $c.clients.Count)
}

Write-Host "=== 2. Logins (password for all demo logins: $Password) ===" -ForegroundColor Cyan
foreach ($u in $Units) {
    $c = $Ctx[$u.code]
    $existing = @((Invoke-Api -Method GET -Url "$MdUrl/api/v1/org-users?organizationRma=$($c.rma)" -Token $AdminTok).Data)
    $want = New-Object System.Collections.ArrayList
    if ($u.admin) {
        $role = 'COMPANY_ADMIN'; if ($u.parent) { $role = 'BRANCH_ADMIN' }
        [void]$want.Add(@{ login = [string]$u.admin.login; role = $role; person = $c.rma; first = [string]$u.admin.firstName; last = [string]$u.admin.lastName; clients = $null })
    }
    foreach ($e in @($u.employees)) {
        if ($e -and $e.login) {
            $parts = ([string]$e.name) -split ' '
            [void]$want.Add(@{ login = [string]$e.login; role = $RoleByEmployeeType[[int]$e.type]; person = [string]$e.rma; first = $parts[1]; last = $parts[0]; clients = $null })
        }
    }
    foreach ($d in @($u.drivers)) {
        if ($d -and $d.login) {
            $parts = ([string]$d.fullName) -split ' '
            [void]$want.Add(@{ login = [string]$d.login; role = 'DRIVER'; person = [string]$d.rma; first = $parts[1]; last = $parts[0]; clients = $null })
        }
    }
    foreach ($cl in @($u.clientLogins)) {
        if ($cl -and $cl.login) {
            $cid = $c.clients[[string]$cl.client].id
            [void]$want.Add(@{ login = [string]$cl.login; role = [string]$cl.role; person = $null; first = [string]$cl.firstName; last = [string]$cl.lastName; clients = @($cid) })
        }
    }
    foreach ($w in $want) {
        $null = Set-DemoUser -Login $w.login -Role $w.role -OrgRma $c.rma -PersonRma $w.person -First $w.first -Last $w.last -ClientIds $w.clients -Existing $existing
        $ok = $script:Tokens.ContainsKey($w.login)
        [void]$script:LoginRows.Add([pscustomobject]@{ Login = $w.login; Role = $w.role; Unit = $u.code; Org = $c.rma; SignIn = $(if ($ok) { 'OK' } else { 'FAIL' }); Sees = '' })
        if ($w.role -eq 'COMPANY_ADMIN' -or $w.role -eq 'BRANCH_ADMIN') { $c.slots['admin'] = $w.login }
    }
    Write-Host ("  {0,-10} logins: {1}" -f $u.code, (($want | ForEach-Object { $_.login }) -join ', '))
}

# ------------------------------------------------------------------ waybills

function Get-SlotToken($c, [string]$slot) {
    $t = $null
    if ($c.slots.ContainsKey($slot)) { $t = Get-UserToken $c.slots[$slot] }
    if (-not $t) { $t = $AdminTok }
    return $t
}

function Get-Waybill([string]$id) { (Invoke-Api -Method GET -Url "$WbUrl/api/v1/waybills/$id" -Token $AdminTok).Data }

function Test-Reached($wb, [string]$target) {
    $s = [string]$wb.status
    if ($target -eq 'WAIT_MED') { return ($s -eq 'CREATED' -and -not $wb.medPassed) }
    if ($target -eq 'WAIT_TECH') { return ($s -eq 'CREATED' -and $wb.medPassed -and -not $wb.techPassed) }
    return ($s -eq $target)
}

function Get-PermitNumber([string]$key, [string]$country, $c) {
    $tok = Get-SlotToken $c 'disp'
    for ($n = 1; $n -le 60; $n++) {
        $num = 'EP-DEMO-' + $key + '-' + $n
        $p = Invoke-Api -Method GET -Url "$MdUrl/api/v1/sync/permit/$num" -Token $tok -AllowError
        if ($p.Status -eq 200 -and $p.Data.valid -and ([string]$p.Data.country) -eq $country) { return $num }
    }
    Add-Problem "no E-PERMIT test number found for country of $key"
    return 'EP-DEMO-' + $key + '-1'
}

function Add-Fuel($c, $wb, $sc, [bool]$withEntry) {
    if (-not $sc -or -not $sc.fuel) { return }
    $tok = Get-SlotToken $c 'azs'
    $url = "$WbUrl/api/v1/fuel-station/waybills/$($wb.id)/fuel"
    $have = Invoke-Api -Method GET -Url $url -Token $tok -AllowError
    if ($have.Status -eq 200 -and @($have.Data | Where-Object { $_ }).Count -gt 0) { return }
    $f = $sc.fuel
    $b = [ordered]@{ fuelType = [int]$f.fuelType; fuelGiven = $f.given; beGiven = $f.given }
    if ($null -ne $f.before) { $b['remainBeforeExit'] = $f.before }
    if ($withEntry -and $null -ne $f.entry) { $b['remainEntry'] = $f.entry }
    $r = Invoke-Api -Method POST -Url $url -Token $tok -Body $b -AllowError
    if ($r.Status -ge 300) { Add-Problem "fuel for $($wb.id): HTTP $($r.Status) $($r.Text)" }
}

function Get-VehicleOdometer([string]$reg) {
    $r = Invoke-Api -Method GET -Url "$MdUrl/api/v1/vehicles?registrationNumber=$reg" -Token $AdminTok -AllowError
    if ($r.Status -ne 200) { return $null }
    foreach ($v in @($r.Data)) { if ($v -and $v.registrationNumber -eq $reg -and $null -ne $v.odometer) { return [int]$v.odometer } }
    return $null
}

function Invoke-Advance($c, $wb, [string]$target, $sc) {
    $disp = Get-SlotToken $c 'disp'
    $doc = Get-SlotToken $c 'doctor'
    $mech = Get-SlotToken $c 'mech'
    $buh = Get-SlotToken $c 'buh'
    $dispRma = $c.signer['disp']; $docRma = $c.signer['doctor']; $mechRma = $c.signer['mech']
    $finalWithEntry = ($target -eq 'COMPLETED' -or $target -eq 'RETURNED')
    for ($i = 0; $i -lt 25; $i++) {
        if (Test-Reached $wb $target) { return $wb }
        $s = [string]$wb.status
        $base = "$WbUrl/api/v1/waybills/$($wb.id)"
        $type = [string]$wb.waybillType
        if (@('COMPLETED', 'CANCELLED', 'EXPIRED', 'ARCHIVED') -contains $s) {
            throw "waybill $($wb.id) is $s, cannot reach $target"
        }
        # A demo "cancelled" waybill is first signed (T1) and then cancelled; a stale draft
        # of a previous day (retire, no scenario) is cancelled right away.
        if ($target -eq 'CANCELLED' -and $s -ne 'BLOCKED' -and $s -ne 'RETURNED' -and ($s -ne 'DRAFT' -or $null -eq $sc)) {
            $reason = [string]$Text.retireReason
            if ($sc -and $sc.cancelReason) { $reason = [string]$sc.cancelReason }
            $wb = (Invoke-Api -Method POST -Url "$base/cancel" -Token $disp -Body @{ reason = $reason; actor = 'demo' }).Data
            continue
        }
        if ($s -eq 'DRAFT') {
            $days = [Math]::Min(3, $MaxDays[$type])
            if ($sc -and $sc.days) { $days = [int]$sc.days }
            $wb = (Invoke-Api -Method POST -Url "$base/titles/t1" -Token $disp -Body @{ dispatcherRma = $dispRma; validityDays = $days }).Data
        } elseif ($s -eq 'CREATED') {
            if (-not $wb.medPassed) {
                $pass = ($target -ne 'MED_REJECTED')
                $ind = [ordered]@{ pressure = '120/80'; pulse = 72; temperature = 36.6; alcotest = 0 }
                if (-not $pass) { $ind = [ordered]@{ pressure = '165/105'; pulse = 104; temperature = 37.4; alcotest = 0 } }
                $wb = (Invoke-Api -Method POST -Url "$base/confirm-med" -Token $doc -Body @{ employeeRma = $docRma; passed = $pass; indicators = $ind }).Data
            } elseif (-not $wb.techPassed) {
                $pass = ($target -ne 'TECH_REJECTED')
                $chk = [ordered]@{}
                foreach ($k in @('brakes', 'lights', 'tires', 'steering', 'fluids', 'mirrors', 'firstaid', 'extinguisher', 'documents', 'general')) { $chk[$k] = 'OK' }
                $chk['fuelPercent'] = '75'
                if (-not $pass) {
                    $chk['brakes'] = [string]$Text.techBroken
                    if ($sc -and $sc.techNote) { $chk['notes'] = [string]$sc.techNote }
                }
                $wb = (Invoke-Api -Method POST -Url "$base/confirm-tech" -Token $mech -Body @{ employeeRma = $mechRma; passed = $pass; checklist = $chk }).Data
            } else {
                throw "waybill $($wb.id) is CREATED with both checks passed"
            }
        } elseif ($s -eq 'AWAITING_PAYMENT') {
            $wb = (Invoke-Api -Method POST -Url "$base/confirm-payment" -Token $buh -Body @{ method = 'CASH'; externalRef = 'DEMO-' + $Day }).Data
        } elseif ($s -eq 'PAID') {
            $wb = Get-Waybill $wb.id
        } elseif ($s -eq 'READY') {
            $dl = $null
            if ($c.driverLogin.ContainsKey([string]$wb.driverRma)) { $dl = Get-UserToken $c.driverLogin[[string]$wb.driverRma] }
            if ($dl) {
                [void](Invoke-Api -Method POST -Url "$WbUrl/api/v1/mobile/waybills/$($wb.id)/accept" -Token $dl -Body @{})
                $wb = Get-Waybill $wb.id
            } else {
                $wb = (Invoke-Api -Method POST -Url "$base/issue" -Token $disp -Body @{ driverConfirmation = 'PIN' }).Data
            }
        } elseif ($s -eq 'ISSUED') {
            # Exit odometer = what the vehicle card shows now (it may have grown since the waybill
            # was written, if another waybill of this vehicle was closed in between).
            $act = [ordered]@{ dispatcherRma = $dispRma }
            $vo = Get-VehicleOdometer ([string]$wb.vehicleRegNumber)
            $snap = Get-Prop (Get-Prop $wb 'vehicleSnapshot') 'odometer'
            if ($null -ne $vo -and ($null -eq $snap -or [int]$vo -ge [int]$snap)) { $act['odometerExit'] = [int]$vo }
            $wb = (Invoke-Api -Method POST -Url "$base/activate" -Token $disp -Body $act).Data
            Add-Fuel $c $wb $sc $finalWithEntry
        } elseif ($s -eq 'ACTIVE') {
            if ($target -eq 'BLOCKED') {
                if (-not $InspectorTok) { throw 'no inspector token' }
                $bl = ConvertTo-Plain $sc.block
                $bl['actor'] = $InspectorUser
                $wb = (Invoke-Api -Method POST -Url "$base/block" -Token $InspectorTok -Body $bl).Data
            } else {
                $km = 50
                if ($sc -and $sc.km) { $km = [int]$sc.km }
                $exit = 0
                if ($null -ne $wb.odometerExit) { $exit = [int]$wb.odometerExit }
                # Closing moves the entry odometer into the vehicle card, which never goes down.
                $vo = Get-VehicleOdometer ([string]$wb.vehicleRegNumber)
                if ($null -ne $vo -and [int]$vo -gt $exit) { $exit = [int]$vo }
                $b = [ordered]@{ dispatcherRma = $dispRma; odometerEntry = $exit + $km }
                $ret = $null
                if ($sc) { $ret = $sc.ret }
                if ($ret) {
                    foreach ($k in @('transportWork', 'trips', 'conditionerHours', 'passengersCount', 'motorHoursEntry')) {
                        $val = Get-Prop $ret $k
                        if ($null -ne $val) { $b[$k] = $val }
                    }
                    $ago = Get-Prop $ret 'arrivalHoursAgo'
                    if ($null -ne $ago -and $IntlTypes -contains $type) { $b['arrivalTime'] = $Today.AddHours(-[double]$ago).ToString('yyyy-MM-ddTHH:mm') }
                }
                if ($type -ne 'WB_PAX_INTL') { $b.Remove('passengersCount') }
                if ($type -ne 'WB_SPECIAL') { $b.Remove('motorHoursEntry') }
                # Special machinery is counted in motor hours: without them the calculation is refused.
                if ($type -eq 'WB_SPECIAL' -and -not $b.Contains('motorHoursEntry')) {
                    $mhx = Get-Prop (Get-Prop $wb 'typeData') 'motorHoursExit'
                    if ($null -ne $mhx) { $b['motorHoursEntry'] = [double]$mhx + 8 }
                }
                $wb = (Invoke-Api -Method POST -Url "$base/return" -Token $disp -Body $b).Data
            }
        } elseif ($s -eq 'BLOCKED') {
            $wb = (Invoke-Api -Method POST -Url "$base/unblock" -Token $AdminTok -Body @{ reason = [string]$Text.unblockReason }).Data
        } elseif ($s -eq 'RETURNED') {
            if ($Passenger -contains $type) {
                $titles = @((Invoke-Api -Method GET -Url "$base/titles" -Token $AdminTok).Data)
                if (-not ($titles | Where-Object { $_ -and $_.titleType -eq 'T6' })) {
                    [void](Invoke-Api -Method POST -Url "$base/confirm-med" -Token $doc -Body @{ employeeRma = $docRma; passed = $true; indicators = [ordered]@{ pressure = '125/80'; pulse = 76; temperature = 36.7; alcotest = 0 } })
                }
            }
            $wb = (Invoke-Api -Method POST -Url "$base/close" -Token $disp -Body @{ actor = [string]$Text.closeActor }).Data
        } else {
            throw "waybill $($wb.id): no step from $s to $target"
        }
    }
    throw "waybill $($wb.id): too many steps towards $target"
}

function Send-Gps($wb, $g) {
    $lat0 = [double]$g.lat; $lon0 = [double]$g.lon; $spd = [int]$g.speed
    for ($k = 5; $k -ge 0; $k--) {
        $at = (Get-Date).AddMinutes(-4 * $k).ToString('yyyy-MM-ddTHH:mm:sszzz')
        $body = [ordered]@{ vehicleRegNumber = [string]$wb.vehicleRegNumber; lat = [Math]::Round($lat0 - 0.004 * $k, 6); lon = [Math]::Round($lon0 - 0.005 * $k, 6); speedKmh = $spd; waybillId = [string]$wb.id; recordedAt = $at }
        [void](Invoke-Api -Method POST -Url "$WbUrl/api/v1/gps" -Token $AdminTok -Body $body -AllowError)
    }
}

function Get-DemoKey($wb) {
    $td = Get-Prop $wb 'typeData'
    return [string](Get-Prop $td 'demoKey')
}

if (-not $SkipWaybills) {
    Write-Host "=== 3. Waybills for $Day ===" -ForegroundColor Cyan
    # 3a. retire open demo waybills of previous days so the vehicles and drivers are free again
    $lists = @{}
    foreach ($u in $Units) {
        $c = $Ctx[$u.code]
        $lists[$u.code] = @((Invoke-Api -Method GET -Url "$WbUrl/api/v1/waybills?organizationRma=$($c.rma)" -Token $AdminTok).Data | Where-Object { $_ })
        foreach ($wb in $lists[$u.code]) {
            $key = Get-DemoKey $wb
            if (-not $key -or $key.StartsWith($Day + '|')) { continue }
            $s = [string]$wb.status
            $goal = $null
            if (@('READY', 'ISSUED', 'ACTIVE', 'BLOCKED', 'RETURNED') -contains $s) { $goal = 'COMPLETED' }
            elseif (@('DRAFT', 'CREATED', 'AWAITING_PAYMENT', 'MED_REJECTED', 'TECH_REJECTED') -contains $s) { $goal = 'CANCELLED' }
            if (-not $goal) { continue }
            try {
                $done = Invoke-Advance $c $wb $goal $null
                Write-Host ("  retired {0} ({1}) {2} -> {3}" -f $key, $wb.number, $s, $done.status)
            } catch {
                # Could not be closed properly (e.g. its entry odometer is now below the vehicle card):
                # cancel it instead, so the vehicle and driver are free for today's set.
                $msg = $_.Exception.Message
                try {
                    $cur = Get-Waybill $wb.id
                    if (@('COMPLETED', 'CANCELLED', 'EXPIRED', 'ARCHIVED', 'BLOCKED') -notcontains [string]$cur.status) {
                        $done = (Invoke-Api -Method POST -Url "$WbUrl/api/v1/waybills/$($wb.id)/cancel" -Token $AdminTok -Body @{ reason = [string]$Text.retireReason; actor = 'demo' }).Data
                        Write-Host ("  retired {0} ({1}) {2} -> {3} (close failed: {4})" -f $key, $wb.number, $s, $done.status, $msg)
                    } else { Add-Problem "retire $key failed: $msg" }
                } catch { Add-Problem "retire $key failed: $msg / $($_.Exception.Message)" }
            }
        }
    }
    # 3b. today's set
    foreach ($u in $Units) {
        if ($RetireOnly) { break }
        $c = $Ctx[$u.code]
        if (-not $u.waybills) { continue }
        $avail = @{}
        $at = Invoke-Api -Method GET -Url "$WbUrl/api/v1/waybills/available-types?organizationRma=$($c.rma)" -Token (Get-SlotToken $c 'disp') -AllowError
        if ($at.Status -eq 200) { foreach ($t in @($at.Data)) { $avail[[string]$t.type] = [bool]$t.available } }
        # All of today's waybills per demo key, newest first (the API sorts by creation time).
        $byKey = @{}
        foreach ($wb in @((Invoke-Api -Method GET -Url "$WbUrl/api/v1/waybills?organizationRma=$($c.rma)" -Token $AdminTok).Data | Where-Object { $_ })) {
            $k = Get-DemoKey $wb
            if ($k -and $k.StartsWith($Day + '|')) {
                if (-not $byKey.ContainsKey($k)) { $byKey[$k] = New-Object System.Collections.ArrayList }
                [void]$byKey[$k].Add($wb)
            }
        }
        foreach ($sc in @($u.waybills)) {
            $key = $Day + '|' + $sc.key
            $type = [string]$sc.type
            $wb = $null
            try {
                # Reuse today's waybill for this scenario unless it already ended in some other
                # final status (e.g. closed/cancelled by hand during a rehearsal) - then make a new one.
                if ($byKey.ContainsKey($key)) {
                    foreach ($cand in $byKey[$key]) {
                        $cs0 = [string]$cand.status
                        $dead = (@('COMPLETED', 'CANCELLED', 'EXPIRED', 'ARCHIVED') -contains $cs0) -and ($cs0 -ne [string]$sc.target)
                        if (-not $dead) { $wb = $cand; break }
                    }
                }
                if (-not $wb) {
                    if ($avail.ContainsKey($type) -and -not $avail[$type]) {
                        Add-Problem "$($sc.key): form $type is not available for $($u.code) (disabled or not licensed) - skipped"
                        continue
                    }
                    $td = ConvertTo-Plain $sc.typeData
                    if (-not $td) { $td = [ordered]@{} }
                    $td['demoKey'] = $key
                    if ($sc.permitCountry) {
                        $td['permitNumber'] = Get-PermitNumber $sc.key ([string]$sc.permitCountry) $c
                        $td['visaValidTo'] = D 180
                    }
                    $body = [ordered]@{
                        waybillType = $type; organizationRma = $c.rma; vehicleRegNumber = [string]$sc.vehicle; driverRma = [string]$sc.driver
                        communicationType = [string]$sc.comm; route = [string]$sc.route; typeData = $td
                    }
                    if ($sc.secondDriver) { $body['secondDriverRma'] = [string]$sc.secondDriver }
                    if ($sc.schedule) { $body['schedule'] = [string]$sc.schedule }
                    $wb = (Invoke-Api -Method POST -Url "$WbUrl/api/v1/waybills" -Token (Get-SlotToken $c 'disp') -Body $body).Data
                    if ($sc.consignment) {
                        $cs = ConvertTo-Plain $sc.consignment
                        foreach ($pair in @(@('senderClient', 'senderId', 'senderName'), @('receiverClient', 'receiverId', 'receiverName'))) {
                            if ($cs.Contains($pair[0])) {
                                $cl = $c.clients[[string]$cs[$pair[0]]]
                                if ($cl) { $cs[$pair[1]] = $cl.id; $cs[$pair[2]] = $cl.name }
                                $cs.Remove($pair[0])
                            }
                        }
                        $wb = (Invoke-Api -Method POST -Url "$WbUrl/api/v1/waybills/$($wb.id)/consignment" -Token (Get-SlotToken $c 'disp') -Body $cs).Data
                    }
                }
                $wb = Invoke-Advance $c $wb ([string]$sc.target) $sc
                if ([string]$wb.status -eq 'ACTIVE') {
                    if ($sc.gps) { Send-Gps $wb $sc.gps }
                    if ($sc.inspectionOk -and $InspectorTok) {
                        $ins = Invoke-Api -Method GET -Url "$WbUrl/api/v1/waybills/$($wb.id)/inspections" -Token $AdminTok
                        if (@($ins.Data | Where-Object { $_ }).Count -eq 0) {
                            $b = ConvertTo-Plain $sc.inspectionOk
                            $b['actor'] = $InspectorUser
                            [void](Invoke-Api -Method POST -Url "$WbUrl/api/v1/waybills/$($wb.id)/inspection" -Token $InspectorTok -Body $b)
                        }
                    }
                }
                $calc = ''
                if ([string]$wb.status -eq 'COMPLETED') {
                    if ($sc.kassa -and $c.signer.ContainsKey('buh') -and -not $wb.kassaConfirmedAt) {
                        $wb = (Invoke-Api -Method POST -Url "$WbUrl/api/v1/waybills/$($wb.id)/kassa" -Token (Get-SlotToken $c 'buh') -Body @{ employeeRma = $c.signer['buh']; actor = 'demo' }).Data
                    }
                    $cr = Invoke-Api -Method POST -Url "$WbUrl/api/v1/waybills/$($wb.id)/calculation" -Token (Get-SlotToken $c 'disp') -Body @{} -AllowError
                    $calc = $(if ($cr.Status -eq 200) { 'calc OK' } else { "calc HTTP $($cr.Status)" })
                }
                $state = [string]$wb.status
                if ($state -eq 'CREATED') { $state = $(if ($wb.medPassed) { 'CREATED (wait tech)' } else { 'CREATED (wait med)' }) }
                [void]$script:WaybillRows.Add([pscustomobject]@{ Unit = $u.code; Key = $sc.key; Form = $type; Status = $state; Number = [string]$wb.number; Vehicle = [string]$sc.vehicle; Note = $calc })
            } catch {
                Add-Problem "$($sc.key) ($type): $($_.Exception.Message)"
                $st = ''
                if ($wb -and $wb.id) {
                    $st = [string]$wb.status
                    try { $st = [string](Get-Waybill $wb.id).status } catch { }
                }
                [void]$script:WaybillRows.Add([pscustomobject]@{ Unit = $u.code; Key = $sc.key; Form = $type; Status = "ERROR ($st)"; Number = ''; Vehicle = [string]$sc.vehicle; Note = 'see problems' })
            }
        }
        Write-Host ("  {0,-10} done" -f $u.code)
    }
}

# ------------------------------------------------------------------ verification

if (-not $SkipVerify) {
    Write-Host "=== 4. Checking every demo login ===" -ForegroundColor Cyan
    foreach ($row in $script:LoginRows) {
        $tok = Get-UserToken $row.Login
        if (-not $tok) { $row.SignIn = 'FAIL'; continue }
        $url = $null; $what = ''
        switch ($row.Role) {
            'DRIVER' { $url = "$WbUrl/api/v1/mobile/waybills"; $what = 'own waybills' }
            'FUEL_STATION' { $url = "$WbUrl/api/v1/fuel-station/waybills"; $what = 'waybills at the pump' }
            'DOCTOR' { $url = "$WbUrl/api/v1/waybills?status=CREATED"; $what = 'waiting checks' }
            'MECHANIC' { $url = "$WbUrl/api/v1/waybills?status=CREATED"; $what = 'waiting checks' }
            'ACCOUNTANT' { $url = "$WbUrl/api/v1/waybills?status=AWAITING_PAYMENT"; $what = 'waiting payment' }
            'CLIENT_SENDER' { $url = "$WbUrl/api/v1/consignments"; $what = 'consignment notes' }
            default { $url = "$WbUrl/api/v1/waybills"; $what = 'waybills in scope' }
        }
        $r = Invoke-Api -Method GET -Url $url -Token $tok -AllowError
        if ($r.Status -ne 200) { $row.Sees = "HTTP $($r.Status)"; continue }
        $n = 0
        if ($row.Role -eq 'CLIENT_SENDER') {
            $n = @($r.Data.content | Where-Object { $_ }).Count
            if ($null -ne $r.Data.totalElements) { $n = [int]$r.Data.totalElements }
        } else { $n = @($r.Data | Where-Object { $_ }).Count }
        $row.Sees = "$n $what"
    }
    Write-Host "=== 5. Reports for today ===" -ForegroundColor Cyan
    foreach ($pair in @(@('platform', $AdminTok), @('bus.admin', (Get-UserToken 'bus.admin')))) {
        if (-not $pair[1]) { continue }
        $r = Invoke-Api -Method GET -Url "$WbUrl/api/v1/reports/summary?from=$Day&to=$Day" -Token $pair[1] -AllowError
        if ($r.Status -eq 200) {
            Write-Host ("  summary as {0,-10}: {1}" -f $pair[0], (ConvertTo-Json -InputObject $r.Data.totals -Compress))
        } else { Add-Problem "summary report as $($pair[0]): HTTP $($r.Status)" }
    }
}

# ------------------------------------------------------------------ output

Write-Host ''
Write-Host "=== DEMO LOGINS (password for every login below: $Password) ===" -ForegroundColor Green
$script:LoginRows | Format-Table Login, Role, Unit, Org, SignIn, Sees -AutoSize | Out-String -Width 200 | Write-Host
Write-Host 'Platform logins (passwords: scripts\demo-credentials.ps1):'
foreach ($p in @($Data.platformLogins)) { Write-Host ("  {0,-12} {1}" -f $p.login, $p.role) }
if (-not $SkipWaybills) {
    Write-Host ''
    Write-Host "=== DEMO WAYBILLS ($Day) ===" -ForegroundColor Green
    $script:WaybillRows | Format-Table Unit, Key, Form, Status, Number, Vehicle, Note -AutoSize | Out-String -Width 200 | Write-Host
}
if ($script:Problems.Count -gt 0) {
    Write-Host "=== PROBLEMS: $($script:Problems.Count) ===" -ForegroundColor Yellow
    foreach ($p in $script:Problems) { Write-Host "  - $p" }
    exit 1
}
Write-Host 'Done without problems.' -ForegroundColor Green
exit 0
