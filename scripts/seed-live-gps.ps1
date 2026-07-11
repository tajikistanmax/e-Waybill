# Seed live GPS demo: create several ACTIVE waybills (org 025680800) each on its own
# vehicle/driver and push a fresh GPS ping around Dushanbe, so /monitoring shows a
# live radar picture. Re-runnable: cancels any open PL on the same vehicle first.
# Usage:  powershell -ExecutionPolicy Bypass -File scripts\seed-live-gps.ps1
$ErrorActionPreference = 'Stop'
$kc = 'http://localhost:8180'
$wb = 'http://localhost:8082'
$md = 'http://localhost:8081'

function GetToken($user) {
    $body = "client_id=epd-web&grant_type=password&username=$user&password=$user"
    (Invoke-RestMethod -Method Post -Uri "$kc/realms/epd/protocol/openid-connect/token" -Body $body -ContentType 'application/x-www-form-urlencoded').access_token
}
function PostJson($url, $obj, $headers) {
    $json = $obj | ConvertTo-Json -Depth 8
    Invoke-RestMethod -Method Post -Uri $url -Headers $headers -Body ([Text.Encoding]::UTF8.GetBytes($json)) -ContentType 'application/json; charset=utf-8'
}

$hd = @{ Authorization = "Bearer $(GetToken 'dispatcher')" }
$hdoc = @{ Authorization = "Bearer $(GetToken 'doctor')" }
$hm = @{ Authorization = "Bearer $(GetToken 'mechanic')" }
$ha = @{ Authorization = "Bearer $(GetToken 'admin')" }
Write-Output 'Tokens OK'

# Employees (dispatcher/doctor/mechanic) used as signers
foreach ($e in @(@{rma='111111111';n='Rahimova S.';t=1}, @{rma='222222222';n='Qosimov F.';t=2}, @{rma='333333333';n='Nazarova M.';t=3})) {
    PostJson "$md/api/v1/employees" @{ rma = $e.rma; organizationRma = '025680800'; name = $e.n; type = $e.t } $ha | Out-Null
}

# Demo units: vehicle + driver + live coordinate around Dushanbe (38.5598, 68.7870)
$units = @(
    @{ veh = '0114TJ01'; drv = '461930031'; dname = 'Ahmedzoda Zohid';   brand = 'Akia';   lat = 38.5760; lon = 68.7840; spd = 34 },
    @{ veh = '1215TJ02'; drv = '461930032'; dname = 'Karimov Dilshod';   brand = 'MAZ';    lat = 38.5340; lon = 68.7690; spd = 52 },
    @{ veh = '2316TJ03'; drv = '461930033'; dname = 'Safarov Bahrom';    brand = 'Isuzu';  lat = 38.5980; lon = 68.8250; spd = 61 },
    @{ veh = '3417TJ04'; drv = '461930034'; dname = 'Yusupov Farrukh';   brand = 'Hyundai';lat = 38.5210; lon = 68.7550; spd = 28 },
    @{ veh = '4518TJ05'; drv = '461930035'; dname = 'Nazriev Komron';    brand = 'Golden'; lat = 38.6150; lon = 68.8050; spd = 47 }
)

$openStatuses = @('DRAFT','CREATED','AWAITING_PAYMENT','READY','ISSUED','ACTIVE')
$done = 0
foreach ($u in $units) {
    try {
        # upsert driver + vehicle (valid categories / dates so blocking checks pass)
        PostJson "$md/api/v1/drivers" @{ rma = $u.drv; organizationRma = '025680800'; fullName = $u.dname; licenseNumber = ("AB" + $u.drv); licenseCategories = 'B,C,D'; licenseValidTo = '2028-05-01'; medCertValidTo = '2026-12-31' } $ha | Out-Null
        PostJson "$md/api/v1/vehicles" @{ registrationNumber = $u.veh; organizationRma = '025680800'; transportType = 1; brand = $u.brand; techInspectionValidTo = '2026-10-01'; controlCardValidTo = '2026-09-01' } $ha | Out-Null

        # cancel any open PL on this vehicle (idempotent re-run)
        $open = Invoke-RestMethod "$wb/api/v1/waybills" -Headers $hd
        foreach ($o in $open) {
            if ($o.vehicleRegNumber -eq $u.veh -and $openStatuses -contains $o.status) {
                try { PostJson "$wb/api/v1/waybills/$($o.id)/cancel" @{ reason = 'seed-live-gps reset'; actor = 'seed' } $hd | Out-Null } catch {}
            }
        }

        # full lifecycle to ACTIVE
        $w = PostJson "$wb/api/v1/waybills" @{ waybillType = 'WB_BUS'; organizationRma = '025680800'; vehicleRegNumber = $u.veh; driverRma = $u.drv; communicationType = 'URBAN'; route = ('Route ' + $u.veh) } $hd
        $w = PostJson "$wb/api/v1/waybills/$($w.id)/titles/t1" @{ dispatcherRma = '333333333'; validityDays = 1 } $hd
        $w = PostJson "$wb/api/v1/waybills/$($w.id)/confirm-med" @{ employeeRma = '111111111'; passed = $true; indicators = @{ pulse = 70; alcotest = 0 } } $hdoc
        $w = PostJson "$wb/api/v1/waybills/$($w.id)/confirm-tech" @{ employeeRma = '222222222'; passed = $true; checklist = @{ brakes = 'OK' } } $hm
        $w = PostJson "$wb/api/v1/waybills/$($w.id)/issue" @{ driverConfirmation = 'PIN' } $hd
        $w = PostJson "$wb/api/v1/waybills/$($w.id)/activate" @{ dispatcherRma = '333333333' } $hd

        # fresh GPS ping
        PostJson "$wb/api/v1/gps" @{ vehicleRegNumber = $u.veh; lat = $u.lat; lon = $u.lon; speedKmh = $u.spd; waybillId = $w.id } $ha | Out-Null
        Write-Output ("  OK  {0}  {1}  {2}  status={3}" -f $u.veh, $w.number, $u.dname, $w.status)
        $done++
    } catch {
        Write-Output ("  FAIL {0}: {1}" -f $u.veh, $_.Exception.Message)
    }
}

$live = Invoke-RestMethod "$wb/api/v1/gps/live" -Headers $hd
Write-Output ("Seeded {0}/{1} units. /gps/live now returns {2} positions." -f $done, $units.Count, @($live).Count)
