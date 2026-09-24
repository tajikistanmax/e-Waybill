# =============================================================================
# e2e-all-forms.ps1 - live end-to-end run of EVERY waybill form through the
# whole lifecycle, plus negative branches, calculation, print (PDF) and public
# QR verification. Pure ASCII on purpose (Windows PowerShell 5.1 parses
# BOM-less files as ANSI); all Cyrillic test data lives in e2e-fixture.json.
#
# Lifecycle per form (role -> endpoint):
#   dispatcher create (DRAFT) -> dispatcher T1 (CREATED) -> doctor T2 ->
#   mechanic T3 (+odometer) -> accountant payment (AWAITING_PAYMENT->READY,
#   number) -> driver accept / dispatcher issue (ISSUED) -> dispatcher fuel ->
#   dispatcher T4 activate (ACTIVE) -> work days / consignment / CMR ->
#   dispatcher T5 return (RETURNED, metrics) -> doctor T6 -> dispatcher close
#   (COMPLETED, vehicle odometer pushed to master-data by the service account)
#   -> fuel-calculation + full calculation -> kassa (3-S) -> print PDFs ->
#   QR JWS -> public /verify without token.
# Negative: doctor rejects, mechanic rejects, inspector blocks, cancel,
#   second waybill on a busy vehicle (409), expired documents (422),
#   disabled form type (422), operations out of status (409).
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\e2e-all-forms.ps1
#   ... -Only WB_BUS,WB_TRUCK      run a subset of forms
#   ... -SkipSeed                  do not upsert the fixture fleet
#   ... -SkipNegative              only the happy paths
#   ... -OutDir C:\tmp\e2e         where PDFs and the JSON result go
# Needs: master-data :8081, waybill :8082 (login is inside the platform:
#   POST /api/v1/auth/token), demo logins from scripts\demo-credentials.ps1.
# Exit code: number of failed checks.
# =============================================================================
param(
    [string]$Md = 'http://localhost:8081',
    [string]$Wb = 'http://localhost:8082',
    [string]$OutDir = (Join-Path $env:TEMP 'ewb-e2e'),
    [string[]]$Only = @(),
    [switch]$SkipSeed,
    [switch]$SkipNegative
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\demo-credentials.ps1"
$fx = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'e2e-fixture.json'), [Text.Encoding]::UTF8) | ConvertFrom-Json
$ORG = $fx.organization.rma
$DISP = '333333333'; $DOC = '111111111'; $MECH = '222222222'; $KASSA = '444444444'
New-Item -ItemType Directory -Force $OutDir | Out-Null
$runTag = Get-Date -Format 'yyyyMMdd-HHmmss'

# ----------------------------------------------------------------- HTTP helpers
$script:Tokens = @{}
function Get-Tok([string]$user) {
    if ($script:Tokens.ContainsKey($user)) { return $script:Tokens[$user] }
    $body = @{ username = $user; password = (Get-DemoPassword $user) } | ConvertTo-Json
    $r = Invoke-RestMethod -Method Post -Uri "$Md/api/v1/auth/token" -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
    $script:Tokens[$user] = $r.access_token
    return $r.access_token
}

# Returns @{Status; Body (parsed JSON or $null); Text; Bytes; Len; Ms; Ct}
function Api([string]$user, [string]$method, [string]$url, $body = $null) {
    $req = [System.Net.HttpWebRequest]::Create($url)
    $req.Method = $method
    $req.Timeout = 900000; $req.ReadWriteTimeout = 900000
    if ($user) { $req.Headers.Add('Authorization', 'Bearer ' + (Get-Tok $user)) }
    $req.Accept = 'application/json, application/pdf, */*'
    if ($null -ne $body) {
        $json = if ($body -is [string]) { $body } else { $body | ConvertTo-Json -Depth 20 }
        $bytes = [Text.Encoding]::UTF8.GetBytes($json)
        $req.ContentType = 'application/json; charset=utf-8'
        $req.ContentLength = $bytes.Length
        $s = $req.GetRequestStream(); $s.Write($bytes, 0, $bytes.Length); $s.Close()
    } elseif ($method -ne 'GET') { $req.ContentLength = 0 }
    $sw = [Diagnostics.Stopwatch]::StartNew()
    try { $resp = $req.GetResponse() }
    catch [System.Net.WebException] { $resp = $_.Exception.Response; if ($null -eq $resp) { throw } }
    $ms = New-Object IO.MemoryStream
    $resp.GetResponseStream().CopyTo($ms)
    $o = [pscustomobject]@{ Status = [int]$resp.StatusCode; Ct = $resp.ContentType; Bytes = $ms.ToArray(); Len = 0; Ms = 0; Body = $null; Text = $null }
    $resp.Close(); $sw.Stop()
    $o.Len = $o.Bytes.Length; $o.Ms = $sw.ElapsedMilliseconds
    if ($o.Ct -and ($o.Ct -match 'json' -or $o.Ct -match 'text')) {
        $o.Text = [Text.Encoding]::UTF8.GetString($o.Bytes)
        if ($o.Ct -match 'json') { try { $o.Body = $o.Text | ConvertFrom-Json } catch { } }
    }
    return $o
}

function Detail($r) {
    if ($null -eq $r) { return '' }
    $d = $null
    if ($r.Body -and $r.Body.PSObject.Properties['detail']) { $d = $r.Body.detail }
    elseif ($r.Body -and $r.Body.PSObject.Properties['message']) { $d = $r.Body.message }
    elseif ($r.Text) { $d = $r.Text }
    if ($d -and $d.Length -gt 220) { $d = $d.Substring(0, 220) }
    return "HTTP $($r.Status) $($r.Ms)ms $d"
}

# ----------------------------------------------------------------- results
$script:Results = New-Object System.Collections.ArrayList
$script:Fail = 0
function Check([string]$form, [string]$step, [bool]$ok, [string]$info = '') {
    [void]$script:Results.Add([pscustomobject]@{ form = $form; step = $step; ok = $ok; info = $info })
    if (-not $ok) { $script:Fail++ }
    $mark = if ($ok) { 'PASS' } else { 'FAIL' }
    Write-Output ("  [{0}] {1,-14} {2} {3}" -f $mark, $form, $step, $info)
}

function D([int]$days) { (Get-Date).AddDays($days).ToString('yyyy-MM-dd') }

# ----------------------------------------------------------------- seed
function Seed {
    Write-Output '=== SEED: organization, staff, routes, vehicles, drivers (upsert) ==='
    $o = @{}
    $fx.organization.PSObject.Properties | ForEach-Object { $o[$_.Name] = $_.Value }
    $o.licenseTo = D 730
    $r = Api 'admin-automation' 'POST' "$Md/api/v1/organizations" $o
    Check 'seed' 'organization' ($r.Status -in 200, 201) (Detail $r)
    foreach ($e in $fx.employees) {
        $b = @{ rma = $e.rma; organizationRma = $ORG; name = $e.name; type = $e.type }
        if ($e.type -eq 1) { $b.certNumber = $e.certNumber; $b.certValidTo = D 365 }
        $r = Api 'admin-automation' 'POST' "$Md/api/v1/employees" $b
        Check 'seed' "employee $($e.rma)/t$($e.type)" ($r.Status -in 200, 201) (Detail $r)
    }
    $existing = @((Api 'admin-automation' 'GET' "$Md/api/v1/dictionaries/routes").Body | Where-Object { $_.organizationRma -eq $ORG })
    foreach ($rt in $fx.routes) {
        $b = @{ organizationRma = $ORG }
        $rt.PSObject.Properties | ForEach-Object { $b[$_.Name] = $_.Value }
        $ex = $existing | Where-Object { $_.number -eq $rt.number } | Select-Object -First 1
        if ($ex) { $b.id = $ex.id }
        $r = Api 'admin-automation' 'POST' "$Md/api/v1/dictionaries/routes" $b
        Check 'seed' "route $($rt.number)" ($r.Status -in 200, 201) (Detail $r)
    }
    $all = @($fx.forms) + @($fx.negative)
    foreach ($f in $all) {
        $expired = [bool]($f.PSObject.Properties['expired'] -and $f.expired)
        $v = @{ registrationNumber = $f.plate; organizationRma = $ORG; transportType = $f.tt; brand = $f.brand
                yearManufacture = 2019; techInspectionValidTo = $(if ($expired) { D -10 } else { D 180 })
                controlCardValidTo = D 180; controlCardNumber = 'KK-' + $f.plate; insuranceValidTo = D 180
                techInspectionNumber = 'TO-' + $f.plate; techPassportNumber = 'TP-' + $f.plate; airConditioner = 50 }
        if ($f.PSObject.Properties['capacity']) { $v.capacity = $f.capacity }
        if ($f.PSObject.Properties['carrying']) { $v.carrying = $f.carrying }
        if ($f.PSObject.Properties['fuelType']) { $v.fuelType = $f.fuelType }
        if ($f.tt -eq 6) { $v.intlCertificateNumber = 'IC-' + $f.plate; $v.intlControlCardNumber = 'IKK-' + $f.plate; $v.intlControlCardValidTo = D 180 }
        $r = Api 'admin-automation' 'POST' "$Md/api/v1/vehicles" $v
        Check 'seed' "vehicle $($f.plate)" ($r.Status -in 200, 201) (Detail $r)
        $d = @{ rma = $f.driverRma; organizationRma = $ORG; fullName = $f.driverName; birthDate = '1985-05-05'
                experienceYears = 12; licenseNumber = 'AA' + $f.driverRma.Substring(2); licenseCategories = 'B,C,D,E'
                licenseValidTo = D 900; degree = 1; medCertNumber = 'MS-' + $f.driverRma; medCertValidTo = D 180
                safetyCourseValidTo = D 365; safetyCourseNumber = 'BDD-' + $f.driverRma; phone = '+992900000000'
                passport = 'A' + $f.driverRma.Substring(1); visaValidTo = D 365 }
        $r = Api 'admin-automation' 'POST' "$Md/api/v1/drivers" $d
        Check 'seed' "driver $($f.driverRma)" ($r.Status -in 200, 201) (Detail $r)
    }
}

# ----------------------------------------------------------------- cleanup
function Cleanup {
    Write-Output '=== CLEANUP: finish or cancel open waybills of the fixture vehicles ==='
    $plates = @(@($fx.forms) + @($fx.negative) | ForEach-Object { $_.plate })
    $r = Api 'dispatcher' 'GET' "$Wb/api/v1/waybills?organizationRma=$ORG"
    foreach ($w in @($r.Body)) {
        if ($plates -notcontains $w.vehicleRegNumber) { continue }
        switch ($w.status) {
            'BLOCKED' {
                [void](Api 'admin-automation' 'POST' "$Wb/api/v1/waybills/$($w.id)/unblock" @{ reason = 'e2e cleanup' })
                [void](Api 'dispatcher' 'POST' "$Wb/api/v1/waybills/$($w.id)/cancel" @{ reason = 'e2e cleanup'; actor = $DISP })
            }
            'RETURNED' {
                [void](Api 'doctor' 'POST' "$Wb/api/v1/waybills/$($w.id)/confirm-med" @{ employeeRma = $DOC; passed = $true; indicators = @{ pulse = 72 } })
                [void](Api 'dispatcher' 'POST' "$Wb/api/v1/waybills/$($w.id)/close" @{ actor = $DISP })
            }
            { $_ -in 'DRAFT','CREATED','MED_REJECTED','TECH_REJECTED','AWAITING_PAYMENT','PAID','READY','ISSUED','ACTIVE' } {
                [void](Api 'dispatcher' 'POST' "$Wb/api/v1/waybills/$($w.id)/cancel" @{ reason = 'e2e cleanup'; actor = $DISP })
            }
        }
    }
}

# ----------------------------------------------------------------- form payloads
$script:Countries = $null
function TypeData($f, [string]$permitCountry) {
    $t = $fx.text
    switch ($f.type) {
        'WB_BUS'        { return @{ columnNumber = '3'; brigadeNumber = '12'; routeTypeCode = 1 } }
        'WB_TROLLEYBUS' { return @{ columnNumber = '1'; brigadeNumber = '4' } }
        'WB_MINIBUS'    { return @{ routeTypeCode = 2 } }
        'WB_CAR'        { return @{ serviceKind = 'ROUTE'; workRegions = @(1, 7) } }
        'WB_TAXI'       { return @{ serviceKind = 'TAXI'; workRegions = @(1) } }
        'WB_TRUCK'      { return @{ shipmentKind = 'PIECEWORK'; directionId = 2; workRegions = @(1, 3)
                                    trailers = @(@{ registrationNumber = '9916QB01'; brand = 'SZAP'; carrying = 8000 })
                                    cargo = @{ name = $t.cargoName; unit = 't'; weight = 8.5; packages = 40 } } }
        'WB_TRUCK_INTL' { return @{ visaValidTo = D 200; visaCountry = $permitCountry; loadCountry = $script:TJ
                                    unloadCountry = $permitCountry; loadCity = $t.loadCity; unloadCity = $t.unloadCity
                                    transitCountries = @($permitCountry); cargoName = $t.cargoName
                                    permitNumber = 'EP-2026-0001'; permitType = 'BILATERAL'; bbaNumber = 'BBA-' + $runTag } }
        'WB_PAX_INTL'   { return @{ visaValidTo = D 200; visaCountry = $permitCountry; loadCountry = $script:TJ
                                    unloadCountry = $permitCountry; loadCity = $t.loadCity; unloadCity = $t.unloadCity
                                    permitNumber = 'EP-2026-0001'; permitType = 'BILATERAL' } }
        'WB_SPECIAL'    { return @{ workType = $script:WorkType; motorHoursExit = 1240.5; workObject = $t.workObject } }
    }
    return $null
}

function CommType([string]$type) {
    switch ($type) { 'WB_TRUCK_INTL' { 'INTERNATIONAL' } 'WB_PAX_INTL' { 'INTERNATIONAL' } 'WB_MINIBUS' { 'SUBURBAN' } 'WB_TRUCK' { 'INTERCITY' } default { 'URBAN' } }
}

function NewWaybill($f, [string]$type, [string]$form) {
    $pc = $script:PermitCountry
    $body = @{ waybillType = $type; organizationRma = $ORG; vehicleRegNumber = $f.plate; driverRma = $f.driverRma
               communicationType = (CommType $type); schedule = '06:00-22:00'; specialMark = $fx.text.specialMark }
    if ($f.PSObject.Properties['route'] -and $f.route) { $body.route = $f.route }
    $td = TypeData $f $pc
    if ($td) { $body.typeData = $td }
    if ($type -eq 'WB_TRUCK_INTL') { $body.secondDriverRma = '990000016' }
    return Api 'dispatcher' 'POST' "$Wb/api/v1/waybills" $body
}

# Moves a DRAFT through T1..READY. Returns the waybill object (or $null on failure).
function ToReady($id, [string]$form, [int]$odoExit) {
    $r = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills/$id/titles/t1" @{ dispatcherRma = $DISP; validityDays = 1 }
    Check $form 'T1 dispatcher -> CREATED' ($r.Status -eq 200 -and $r.Body.status -eq 'CREATED') (Detail $r)
    if ($r.Status -ne 200) { return $null }
    $r = Api 'doctor' 'POST' "$Wb/api/v1/waybills/$id/confirm-med" @{ employeeRma = $DOC; passed = $true
            indicators = @{ pulse = 74; pressureSys = 120; pressureDia = 80; temperature = 36.6; alcohol = 0; complaints = '' } }
    Check $form 'T2 doctor (pre-trip)' ($r.Status -eq 200 -and $r.Body.medPassed) (Detail $r)
    $r = Api 'mechanic' 'POST' "$Wb/api/v1/waybills/$id/confirm-tech" @{ employeeRma = $MECH; passed = $true; odometerExit = $odoExit
            checklist = @{ brakes = 'OK'; steering = 'OK'; lights = 'OK'; tyres = 'OK' } }
    Check $form 'T3 mechanic (+odometer)' ($r.Status -eq 200 -and $r.Body.techPassed) ("{0} -> {1}" -f (Detail $r), $r.Body.status)
    $w = $r.Body
    if ($w.status -eq 'AWAITING_PAYMENT') {
        $p = Api 'accountant' 'GET' "$Wb/api/v1/waybills/$id/payment"
        Check $form 'payment card (accountant)' ($p.Status -eq 200) ("amount={0} {1}" -f $p.Body.amount, $p.Body.currency)
        $r = Api 'accountant' 'POST' "$Wb/api/v1/waybills/$id/confirm-payment" @{ method = 'CASH'; externalRef = "E2E-$runTag" }
        Check $form 'payment -> READY + number' ($r.Status -eq 200 -and $r.Body.status -eq 'READY' -and $r.Body.number) ("{0} no={1}" -f (Detail $r), $r.Body.number)
        $w = $r.Body
    } else {
        Check $form 'READY without payment' ($w.status -eq 'READY' -and $w.number) "status=$($w.status) no=$($w.number)"
    }
    if ($w.number) {
        Check $form 'number format' ($w.number -match '^\d{2}-\d{2}-\d{2}-\d{7}-\d$') $w.number
    }
    return $w
}

function VehicleOdometer([string]$plate) {
    $r = Api 'admin-automation' 'GET' "$Md/api/v1/vehicles?registrationNumber=$plate"
    if ($r.Status -eq 200 -and @($r.Body).Count -gt 0) { return [int](@($r.Body)[0].odometer) }
    return -1
}

function SavePdf($r, [string]$name) {
    $p = Join-Path $OutDir $name
    [IO.File]::WriteAllBytes($p, $r.Bytes)
    return $p
}

function IsPdf($r) {
    return ($r.Status -eq 200 -and $r.Len -gt 2000 -and $r.Bytes[0] -eq 0x25 -and $r.Bytes[1] -eq 0x50 -and $r.Bytes[2] -eq 0x44 -and $r.Bytes[3] -eq 0x46)
}

# ----------------------------------------------------------------- happy path per form
function Run-Form($f) {
    $type = $f.type; $form = $type
    Write-Output "=== FORM $type ($($f.plate), driver $($f.driverRma)) ==="
    $pre = Api 'dispatcher' 'GET' "$Wb/api/v1/waybills/preflight?type=$type&organizationRma=$ORG&vehicleRegNumber=$($f.plate)&driverRma=$($f.driverRma)"
    $errs = @($pre.Body.checks | Where-Object { $_.severity -eq 'ERROR' } | ForEach-Object { $_.message })
    Check $form 'preflight eligible' ($pre.Status -eq 200 -and $pre.Body.eligible) ((Detail $pre) + ' ' + ($errs -join '; '))
    $odo0 = VehicleOdometer $f.plate
    $r = NewWaybill $f $type $form
    Check $form 'create (DRAFT)' ($r.Status -eq 201 -and $r.Body.status -eq 'DRAFT') (Detail $r)
    if ($r.Status -ne 201) { return }
    $id = $r.Body.id
    $script:Created[$type] = $id
    $w = ToReady $id $form $odo0
    if (-not $w -or $w.status -ne 'READY') { return }

    # QR is available from READY
    $q = Api 'dispatcher' 'GET' "$Wb/api/v1/waybills/$id/qr"
    Check $form 'QR JWS (READY)' ($q.Status -eq 200 -and $q.Body.jws.Length -gt 100) (Detail $q)

    # hand-over to the driver
    if ($f.driverRma -eq '555555555') {
        $m = Api 'driver' 'GET' "$Wb/api/v1/mobile/waybills"
        Check $form 'driver cabinet lists it' ($m.Status -eq 200 -and (@($m.Body | Where-Object { $_.id -eq $id }).Count -eq 1)) (Detail $m)
        $r = Api 'driver' 'POST' "$Wb/api/v1/mobile/waybills/$id/accept"
        Check $form 'driver accepts (ISSUED)' ($r.Status -eq 200 -and $r.Body.status -eq 'ISSUED') (Detail $r)
    } else {
        $r = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills/$id/issue" @{ driverConfirmation = 'PIN' }
        Check $form 'issue to driver (ISSUED)' ($r.Status -eq 200 -and $r.Body.status -eq 'ISSUED') (Detail $r)
    }

    # fuel: prefill + record (dispatcher; fuel station of the same org if configured)
    $ft = if ($f.PSObject.Properties['fuelType']) { [int]$f.fuelType } else { 2 }
    $pf = Api 'dispatcher' 'GET' "$Wb/api/v1/waybills/$id/fuel-prefill?fuelType=$ft"
    Check $form 'fuel prefill' ($pf.Status -eq 200) (Detail $pf)
    $r = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills/$id/fuel" @{ fuelType = $ft; fuelGiven = 60; remainBeforeExit = 15; beGiven = 60 }
    Check $form 'fuel issued (dispatcher)' ($r.Status -eq 201) (Detail $r)

    # T4 exit
    $r = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills/$id/activate" @{ dispatcherRma = $DISP; odometerExit = $odo0 }
    Check $form 'T4 exit (ACTIVE)' ($r.Status -eq 200 -and $r.Body.status -eq 'ACTIVE') (Detail $r)
    if ($r.Status -ne 200) { return }
    $exit = [int]$r.Body.odometerExit

    # work on the line
    $dist = if ($type -in 'WB_TRUCK_INTL', 'WB_PAX_INTL') { 640 } else { 160 }
    if ($type -in 'WB_BUS', 'WB_TROLLEYBUS', 'WB_MINIBUS', 'WB_CAR', 'WB_TAXI', 'WB_PAX_INTL') {
        $wd = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills/$id/work-days" @{ workDate = (Get-Date).ToString('yyyy-MM-dd')
                exitTime = '06:00'; entryTime = '18:30'; odometerExit = $exit; odometerEntry = ($exit + $dist)
                laps = 5; revenue = 1250.50; conditionerHours = 2 }
        Check $form 'work day (laps/revenue)' ($wd.Status -eq 201) (Detail $wd)
    }
    if ($type -eq 'WB_TRUCK') {
        $c = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills/$id/consignment" @{ senderName = $fx.text.sender; senderAddress = $fx.text.senderAddress
                receiverName = $fx.text.receiver; receiverAddress = $fx.text.receiverAddress; cargoName = $fx.text.cargoName
                cargoVolume = 8.5; cargoStatCode = '5201'; submittedDocuments = 'TTN'; cargoNumber = 101
                cargoOperations = @(@{ operation = 'LOAD'; arrival = '08:00'; departure = '09:00' }, @{ operation = 'UNLOAD'; arrival = '13:00'; departure = '14:00' }) }
        Check $form 'consignment (appendix)' ($c.Status -eq 200) (Detail $c)
    }
    if ($type -eq 'WB_TRUCK_INTL') {
        $c = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills/$id/consignment" @{ senderName = $fx.text.sender; senderAddress = $fx.text.senderAddress
                receiverName = $fx.text.receiver; receiverAddress = $fx.text.receiverAddress; cargoName = $fx.text.cargoName
                cargoVolume = 18; cargoStatCode = '5201'; submittedDocuments = 'CMR, invoice'; tripsCount = 1 }
        Check $form 'CMR data' ($c.Status -eq 200) (Detail $c)
    }
    $e = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills/$id/expenses" @{ expenseType = 'PARKING'; amount = 15; currency = 'TJS'; description = 'e2e parking' }
    Check $form 'trip expense' ($e.Status -in 200, 201) (Detail $e)

    # T5 return
    $ret = @{ dispatcherRma = $DISP; odometerEntry = ($exit + $dist) }
    switch ($type) {
        'WB_TRUCK'      { $ret.transportWork = 1280; $ret.trips = 2 }
        'WB_TRUCK_INTL' { $ret.transportWork = 11520; $ret.trips = 1; $ret.arrivalTime = (Get-Date).AddHours(-3).ToString('yyyy-MM-ddTHH:mm') }
        'WB_PAX_INTL'   { $ret.passengersCount = 38; $ret.arrivalTime = (Get-Date).AddHours(-2).ToString('yyyy-MM-ddTHH:mm'); $ret.conditionerHours = 4 }
        'WB_SPECIAL'    { $ret.motorHoursEntry = 1248.0 }
        default         { $ret.conditionerHours = 2 }
    }
    $r = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills/$id/return" $ret
    Check $form 'T5 return (RETURNED)' ($r.Status -eq 200 -and $r.Body.status -eq 'RETURNED') (Detail $r)
    if ($r.Status -ne 200) { return }

    $fc = Api 'accountant' 'GET' "$Wb/api/v1/waybills/$id/fuel-calculation"
    # trolleybus is electric: no fuel norm, an explicit 422 with the reason (card hides the button)
    $fcOk = if ($type -eq 'WB_TROLLEYBUS') { $fc.Status -eq 422 } else { $fc.Status -eq 200 }
    Check $form 'fuel norm calc' $fcOk ("{0} :: {1}" -f (Detail $fc).Substring(0, [Math]::Min(14, (Detail $fc).Length)), ($fc.Text -replace '\s+', ' ').Substring(0, [Math]::Min(260, [int]$fc.Text.Length)))

    # T6 + close. Passenger forms (policy default: WB_BUS/TROLLEYBUS/MINIBUS/TAXI/PAX_INTL) must
    # refuse to close without the post-trip exam; all forms accept T6 in RETURNED, exactly once.
    if ($type -in 'WB_BUS', 'WB_TROLLEYBUS', 'WB_MINIBUS', 'WB_TAXI', 'WB_PAX_INTL') {
        $r = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills/$id/close" @{ actor = $DISP }
        Check $form 'close without T6 -> 409' ($r.Status -eq 409) (Detail $r)
    }
    $r = Api 'doctor' 'POST' "$Wb/api/v1/waybills/$id/confirm-med" @{ employeeRma = $DOC; passed = $true; indicators = @{ pulse = 78; pressureSys = 125; pressureDia = 82; alcohol = 0 } }
    Check $form 'T6 doctor (post-trip)' ($r.Status -eq 200) (Detail $r)
    $r = Api 'doctor' 'POST' "$Wb/api/v1/waybills/$id/confirm-med" @{ employeeRma = $DOC; passed = $true; indicators = @{ pulse = 78 } }
    Check $form 'second T6 -> 409' ($r.Status -eq 409) (Detail $r)
    $r = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills/$id/close" @{ actor = $DISP }
    Check $form 'close (COMPLETED)' ($r.Status -eq 200 -and $r.Body.status -eq 'COMPLETED') (Detail $r)
    $odo1 = VehicleOdometer $f.plate
    Check $form 'vehicle odometer pushed' ($odo1 -eq ($exit + $dist)) "master-data odometer $odo0 -> $odo1 (expected $($exit + $dist))"

    # calculation
    $sup = @{ earning = 1250.50 }
    $c = Api 'accountant' 'POST' "$Wb/api/v1/waybills/$id/calculation" $sup
    $kind = if ($c.Body) { $c.Body.kind } else { '' }
    $sum = ''
    if ($c.Body -and $c.Body.passenger) { $sum = ($c.Body.passenger | ConvertTo-Json -Depth 3 -Compress) }
    elseif ($c.Body -and $c.Body.cargo) { $sum = ($c.Body.cargo | ConvertTo-Json -Depth 3 -Compress) }
    if ($sum.Length -gt 300) { $sum = $sum.Substring(0, 300) }
    Check $form 'full calculation' ($c.Status -eq 200) ("{0} kind={1} {2}" -f (Detail $c).Substring(0, [Math]::Min(14, (Detail $c).Length)), $kind, $sum)
    if ($c.Body -and $c.Body.notes) { Write-Output ("      notes: " + (($c.Body.notes | Select-Object -First 4) -join ' | ')) }

    if ($type -in 'WB_CAR', 'WB_TAXI') {
        $k = Api 'accountant' 'POST' "$Wb/api/v1/waybills/$id/kassa" @{ employeeRma = $KASSA }
        Check $form 'kassa (revenue handed in)' ($k.Status -eq 200 -and $k.Body.kassaConfirmedAt) (Detail $k)
    }

    # print
    $p = Api 'dispatcher' 'GET' "$Wb/api/v1/waybills/$id/print.pdf"
    $path = if ($p.Status -eq 200) { SavePdf $p "$type-$runTag.pdf" } else { '' }
    Check $form 'print.pdf' (IsPdf $p) ("{0} bytes {1}ms {2}" -f $p.Len, $p.Ms, $path)
    if ($type -eq 'WB_TRUCK') {
        $p = Api 'dispatcher' 'GET' "$Wb/api/v1/waybills/$id/print-attachment.pdf"
        $path = if ($p.Status -eq 200) { SavePdf $p "$type-attachment-$runTag.pdf" } else { '' }
        Check $form 'print-attachment.pdf' (IsPdf $p) ("{0} bytes {1}" -f $p.Len, $path)
    }
    if ($type -eq 'WB_TRUCK_INTL') {
        $p = Api 'dispatcher' 'GET' "$Wb/api/v1/waybills/$id/print-cmr.pdf"
        $path = if ($p.Status -eq 200) { SavePdf $p "$type-cmr-$runTag.pdf" } else { '' }
        Check $form 'print-cmr.pdf' (IsPdf $p) ("{0} bytes {1}" -f $p.Len, $path)
    }

    # public QR verification (no token)
    $q = Api 'dispatcher' 'GET' "$Wb/api/v1/waybills/$id/qr"
    if ($q.Status -eq 200) {
        $v = Api $null 'GET' "$Wb/api/v1/verify/$($q.Body.jws)"
        $st = if ($v.Body) { ($v.Body | ConvertTo-Json -Depth 4 -Compress) } else { '' }
        Check $form 'public /verify (no token)' ($v.Status -eq 200 -and $st -match 'COMPLETED') ("HTTP {0} {1}" -f $v.Status, $st.Substring(0, [Math]::Min(200, $st.Length)))
    }
    $h = Api 'dispatcher' 'GET' "$Wb/api/v1/waybills/$id/status-history"
    Check $form 'status history' ($h.Status -eq 200 -and @($h.Body).Count -ge 7) ("{0} events" -f @($h.Body).Count)
    $t = Api 'dispatcher' 'GET' "$Wb/api/v1/waybills/$id/titles"
    $tt = (@($t.Body) | ForEach-Object { $_.titleType }) -join ','
    Check $form 'titles T1..T6' ($tt -match 'T1' -and $tt -match 'T2' -and $tt -match 'T3' -and $tt -match 'T4' -and $tt -match 'T5' -and $tt -match 'T6') $tt
    $t45 = @($t.Body | Where-Object { $_.titleType -in 'T4', 'T5' })
    $named = @($t45 | Where-Object { $_.data.dispatcher })
    Check $form 'T4/T5 carry dispatcher name' ($t45.Count -eq 2 -and $named.Count -eq 2) (($t45 | ForEach-Object { "$($_.titleType)=$($_.data.dispatcher)" }) -join '; ')
}

# ----------------------------------------------------------------- negatives
function Run-Negative {
    $neg = @{}; foreach ($n in $fx.negative) { $neg[$n.key] = $n }
    $form = 'NEG'
    Write-Output '=== NEGATIVE BRANCHES ==='

    # disabled form type (classifier WAYBILL_TYPE active=false)
    $n = $neg['reserve']
    $r = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills" @{ waybillType = 'WB_DANGEROUS'; organizationRma = $ORG; vehicleRegNumber = $n.plate; driverRma = $n.driverRma; typeData = @{ adrClass = '3' } }
    Check $form 'disabled type WB_DANGEROUS -> 422' ($r.Status -eq 422) (Detail $r)

    # doctor rejects -> MED_REJECTED -> replace driver -> CREATED
    $n = $neg['med']
    $r = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills" @{ waybillType = 'WB_BUS'; organizationRma = $ORG; vehicleRegNumber = $n.plate; driverRma = $n.driverRma; communicationType = 'URBAN'; route = 'QA1' }
    if ($r.Status -eq 201) {
        $id = $r.Body.id
        [void](Api 'dispatcher' 'POST' "$Wb/api/v1/waybills/$id/titles/t1" @{ dispatcherRma = $DISP; validityDays = 1 })
        $r = Api 'doctor' 'POST' "$Wb/api/v1/waybills/$id/confirm-med" @{ employeeRma = $DOC; passed = $false; indicators = @{ pulse = 120; alcohol = 0.4; reason = 'alcohol' } }
        Check $form 'doctor rejects -> MED_REJECTED' ($r.Status -eq 200 -and $r.Body.status -eq 'MED_REJECTED') (Detail $r)
        $r = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills/$id/issue" @{ driverConfirmation = 'PIN' }
        Check $form 'issue after med reject -> 409' ($r.Status -eq 409) (Detail $r)
        $r = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills/$id/replace-driver" @{ newDriverRma = $neg['reserve'].driverRma; dispatcherRma = $DISP }
        Check $form 'replace driver -> CREATED' ($r.Status -eq 200 -and $r.Body.status -eq 'CREATED') (Detail $r)
        [void](Api 'dispatcher' 'POST' "$Wb/api/v1/waybills/$id/cancel" @{ reason = 'e2e'; actor = $DISP })
    } else { Check $form 'create for med-reject' $false (Detail $r) }

    # mechanic rejects -> TECH_REJECTED
    $n = $neg['tech']
    $r = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills" @{ waybillType = 'WB_BUS'; organizationRma = $ORG; vehicleRegNumber = $n.plate; driverRma = $n.driverRma; communicationType = 'URBAN'; route = 'QA1' }
    if ($r.Status -eq 201) {
        $id = $r.Body.id
        [void](Api 'dispatcher' 'POST' "$Wb/api/v1/waybills/$id/titles/t1" @{ dispatcherRma = $DISP; validityDays = 1 })
        [void](Api 'doctor' 'POST' "$Wb/api/v1/waybills/$id/confirm-med" @{ employeeRma = $DOC; passed = $true; indicators = @{ pulse = 70 } })
        $r = Api 'mechanic' 'POST' "$Wb/api/v1/waybills/$id/confirm-tech" @{ employeeRma = $MECH; passed = $false; checklist = @{ brakes = 'FAIL'; comment = 'brakes worn' } }
        Check $form 'mechanic rejects -> TECH_REJECTED' ($r.Status -eq 200 -and $r.Body.status -eq 'TECH_REJECTED') (Detail $r)
        $r = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills/$id/activate" @{ dispatcherRma = $DISP }
        Check $form 'exit after tech reject -> 409' ($r.Status -eq 409) (Detail $r)
        $r = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills/$id/cancel" @{ reason = $fx.text.cancelReason; actor = $DISP }
        Check $form 'cancel TECH_REJECTED' ($r.Status -eq 200 -and $r.Body.status -eq 'CANCELLED') (Detail $r)
    } else { Check $form 'create for tech-reject' $false (Detail $r) }

    # busy vehicle: second waybill -> 409; inspector block; cancel blocked -> 409; admin unblock
    $n = $neg['block']
    $r = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills" @{ waybillType = 'WB_BUS'; organizationRma = $ORG; vehicleRegNumber = $n.plate; driverRma = $n.driverRma; communicationType = 'URBAN'; route = 'QA1' }
    if ($r.Status -eq 201) {
        $id = $r.Body.id
        $w = ToReady $id $form (VehicleOdometer $n.plate)
        $r2 = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills" @{ waybillType = 'WB_BUS'; organizationRma = $ORG; vehicleRegNumber = $n.plate; driverRma = $neg['cancel'].driverRma; communicationType = 'URBAN' }
        Check $form 'second waybill on busy vehicle -> 409' ($r2.Status -eq 409) (Detail $r2)
        $r2 = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills" @{ waybillType = 'WB_BUS'; organizationRma = $ORG; vehicleRegNumber = $neg['cancel'].plate; driverRma = $n.driverRma; communicationType = 'URBAN' }
        Check $form 'second waybill on busy driver -> 409' ($r2.Status -eq 409) (Detail $r2)
        [void](Api 'dispatcher' 'POST' "$Wb/api/v1/waybills/$id/issue" @{ driverConfirmation = 'PIN' })
        [void](Api 'dispatcher' 'POST' "$Wb/api/v1/waybills/$id/activate" @{ dispatcherRma = $DISP })
        $r = Api 'inspector-automation' 'POST' "$Wb/api/v1/waybills/$id/block" @{ reasonCode = 'NO_DOCUMENT'; description = $fx.text.blockReason; place = 'Dushanbe, post 3'; lat = 38.56; lon = 68.78; protocolNumber = "AKT-$runTag" }
        if ($r.Status -eq 400 -or $r.Status -eq 422) {
            $reasons = Api 'inspector-automation' 'GET' "$Wb/api/v1/waybills/inspection-reasons"
            $code = @($reasons.Body)[0].code
            $r = Api 'inspector-automation' 'POST' "$Wb/api/v1/waybills/$id/block" @{ reasonCode = $code; description = $fx.text.blockReason; place = 'Dushanbe, post 3'; protocolNumber = "AKT-$runTag" }
        }
        Check $form 'inspector blocks (BLOCKED)' ($r.Status -eq 200 -and $r.Body.status -eq 'BLOCKED') (Detail $r)
        $r = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills/$id/cancel" @{ reason = 'try'; actor = $DISP }
        Check $form 'cancel BLOCKED -> 409' ($r.Status -eq 409) (Detail $r)
        $r = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills/$id/unblock" @{ reason = 'try' }
        Check $form 'dispatcher unblock -> 403' ($r.Status -eq 403) (Detail $r)
        $r = Api 'admin-automation' 'POST' "$Wb/api/v1/waybills/$id/unblock" @{ reason = 'e2e: act reviewed' }
        Check $form 'admin unblock' ($r.Status -eq 200 -and $r.Body.status -ne 'BLOCKED') ("{0} -> {1}" -f (Detail $r), $r.Body.status)
        $r = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills/$id/cancel" @{ reason = $fx.text.cancelReason; actor = $DISP }
        Check $form 'cancel after unblock' ($r.Status -eq 200 -and $r.Body.status -eq 'CANCELLED') (Detail $r)
        $r = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills/$id/activate" @{ dispatcherRma = $DISP }
        Check $form 'operation on CANCELLED -> 409' ($r.Status -eq 409) (Detail $r)
    } else { Check $form 'create for block' $false (Detail $r) }

    # cancel a fresh DRAFT/CREATED
    $n = $neg['cancel']
    $r = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills" @{ waybillType = 'WB_BUS'; organizationRma = $ORG; vehicleRegNumber = $n.plate; driverRma = $n.driverRma; communicationType = 'URBAN' }
    if ($r.Status -eq 201) {
        $id = $r.Body.id
        [void](Api 'dispatcher' 'POST' "$Wb/api/v1/waybills/$id/titles/t1" @{ dispatcherRma = $DISP; validityDays = 1 })
        $r = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills/$id/cancel" @{ reason = $fx.text.cancelReason; actor = $DISP }
        Check $form 'cancel CREATED' ($r.Status -eq 200 -and $r.Body.status -eq 'CANCELLED') (Detail $r)
        $r = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills/$id/cancel" @{ reason = 'again'; actor = $DISP }
        Check $form 'cancel twice -> 409' ($r.Status -eq 409) (Detail $r)
        $p = Api 'dispatcher' 'GET' "$Wb/api/v1/waybills/$id/print.pdf"
        Check $form 'print of CANCELLED (no number)' ($p.Status -in 200, 409, 422) ("HTTP {0} {1} bytes" -f $p.Status, $p.Len)
    } else { Check $form 'create for cancel' $false (Detail $r) }

    # expired technical inspection -> 422
    $n = $neg['expired']
    $r = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills" @{ waybillType = 'WB_BUS'; organizationRma = $ORG; vehicleRegNumber = $n.plate; driverRma = $n.driverRma; communicationType = 'URBAN' }
    Check $form 'expired tech inspection -> 422' ($r.Status -eq 422) (Detail $r)
    $pre = Api 'dispatcher' 'GET' "$Wb/api/v1/waybills/preflight?type=WB_BUS&organizationRma=$ORG&vehicleRegNumber=$($n.plate)&driverRma=$($n.driverRma)"
    Check $form 'preflight shows expired' ($pre.Status -eq 200 -and -not $pre.Body.eligible) ((@($pre.Body.checks) | ForEach-Object { $_.code }) -join ',')

    # form/vehicle mismatch and missing required form fields
    $r = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills" @{ waybillType = 'WB_TRUCK'; organizationRma = $ORG; vehicleRegNumber = $neg['cancel'].plate; driverRma = $neg['cancel'].driverRma }
    Check $form '2-B without shipmentKind -> 422' ($r.Status -eq 422) (Detail $r)
    $r = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills" @{ waybillType = 'WB_TRUCK_INTL'; organizationRma = $ORG; vehicleRegNumber = $neg['cancel'].plate; driverRma = $neg['cancel'].driverRma; typeData = @{ visaCountry = 'X'; loadCountry = 'X'; unloadCountry = 'Y'; permitNumber = 'EP-BAD-1' } }
    Check $form '5B-BM with invalid permit -> 422' ($r.Status -eq 422) (Detail $r)
    # foreign organization
    $r = Api 'dispatcher' 'POST' "$Wb/api/v1/waybills" @{ waybillType = 'WB_BUS'; organizationRma = '010001338'; vehicleRegNumber = $neg['cancel'].plate; driverRma = $neg['cancel'].driverRma }
    Check $form 'foreign organization -> 403' ($r.Status -eq 403) (Detail $r)
    # roles that must not issue
    $r = Api 'doctor' 'POST' "$Wb/api/v1/waybills" @{ waybillType = 'WB_BUS'; organizationRma = $ORG; vehicleRegNumber = $neg['cancel'].plate; driverRma = $neg['cancel'].driverRma }
    Check $form 'doctor cannot issue -> 403' ($r.Status -eq 403) (Detail $r)
}

# ----------------------------------------------------------------- main
$t0 = Get-Date
Write-Output "=== e2e-all-forms $runTag  md=$Md wb=$Wb out=$OutDir ==="
$script:Created = @{}
if (-not $SkipSeed) { Seed }
Cleanup
$cls = Api 'dispatcher' 'GET' "$Md/api/v1/classifiers?category=COUNTRY"
$script:TJ = (@($cls.Body) | Where-Object { $_.code -eq 'TJ' } | Select-Object -First 1).nameRu
$wt = Api 'dispatcher' 'GET' "$Md/api/v1/classifiers?category=WORK_TYPE"
$script:WorkType = (@($wt.Body) | Select-Object -First 1).nameRu
$pm = Api 'admin-automation' 'GET' "$Md/api/v1/sync/permit/EP-2026-0001"
$script:PermitCountry = $pm.Body.country
$at = Api 'dispatcher' 'GET' "$Wb/api/v1/waybills/available-types?organizationRma=$ORG"
$avail = @($at.Body | Where-Object { $_.available } | ForEach-Object { $_.type })
Check 'setup' 'available-types' ($at.Status -eq 200 -and $avail.Count -ge 9) ("available: " + ($avail -join ','))

foreach ($f in $fx.forms) {
    if ($Only.Count -gt 0 -and $Only -notcontains $f.type) { continue }
    try { Run-Form $f } catch { Check $f.type 'EXCEPTION' $false $_.Exception.Message }
}
if (-not $SkipNegative) {
    try { Run-Negative } catch { Check 'NEG' 'EXCEPTION' $false $_.Exception.Message }
}

$elapsed = [int]((Get-Date) - $t0).TotalSeconds
$json = Join-Path $OutDir "e2e-$runTag.json"
[IO.File]::WriteAllText($json, ($script:Results | ConvertTo-Json -Depth 4), (New-Object Text.UTF8Encoding($false)))
Write-Output ''
Write-Output '=== SUMMARY per form ==='
$script:Results | Group-Object form | ForEach-Object {
    $bad = @($_.Group | Where-Object { -not $_.ok })
    Write-Output ("  {0,-14} {1,3} checks, {2} failed {3}" -f $_.Name, $_.Count, $bad.Count, (($bad | ForEach-Object { $_.step }) -join '; '))
}
Write-Output ("=== TOTAL: {0} checks, {1} failed, {2}s. Results: {3}" -f $script:Results.Count, $script:Fail, $elapsed, $json)
exit $script:Fail
