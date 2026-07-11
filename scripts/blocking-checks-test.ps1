# =====================================================================
# Регресс-щит блокирующих проверок безопасности перед выдачей ПЛ:
#   1) соответствие категории ВУ типу ТС (неквалифицированный водитель);
#   2) непрерывность одометра при выезде (антифрод-скрутка).
# Запуск: powershell -File scripts\blocking-checks-test.ps1
# Идемпотентен: временно меняет категорию сид-водителя и возвращает её,
# созданные тестовые ПЛ аннулирует (одометр ТС не трогает — close не вызывается).
# Требует: waybill :8082, Keycloak :8180, docker epd-postgres, сид-данные
#          (ТС 0114TJ01 тип 1 автобус, водитель 461930031 кат.D, диспетчер/врач/механик).
# =====================================================================
$ErrorActionPreference = 'Stop'
$wb = 'http://localhost:8082'; $kc = 'http://localhost:8180'; $pg = 'epd-postgres'
$pass = 0; $fail = 0
function Chk($name, $cond) {
    if ($cond) { $script:pass++; Write-Output "  [PASS] $name" }
    else { $script:fail++; Write-Output "  [FAIL] $name" }
}
function Tok($u) { (Invoke-RestMethod -Method Post -Uri "$kc/realms/epd/protocol/openid-connect/token" -Body "client_id=epd-web&grant_type=password&username=$u&password=$u" -ContentType 'application/x-www-form-urlencoded').access_token }
$hd = @{ Authorization = "Bearer $(Tok 'dispatcher')" }
$hdoc = @{ Authorization = "Bearer $(Tok 'doctor')" }
$hm = @{ Authorization = "Bearer $(Tok 'mechanic')" }
function PJ($path, $body, $h) { Invoke-RestMethod -Method Post -Uri "$wb$path" -Headers $h -Body ([Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 6))) -ContentType 'application/json; charset=utf-8' }
function Code($path, $body, $h) {
    try { $r = Invoke-WebRequest -Method Post -Uri "$wb$path" -Headers $h -Body ([Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 6))) -ContentType 'application/json; charset=utf-8' -UseBasicParsing; [int]$r.StatusCode }
    catch { if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode.value__ } else { -1 } }
}
function Cancel($id) { if ($id) { try { PJ "/api/v1/waybills/$id/cancel" @{ reason = 'test'; actor = 'test' } $hd | Out-Null } catch {} } }
function CleanVeh($plate) {
    $open = Invoke-RestMethod "$wb/api/v1/waybills" -Headers $hd
    foreach ($o in $open) { if ($o.vehicleRegNumber -eq $plate -and @('DRAFT', 'CREATED', 'AWAITING_PAYMENT', 'PAID', 'READY', 'ISSUED', 'ACTIVE') -contains $o.status) { Cancel $o.id } }
}
$create = @{ waybillType = 'WB_BUS'; organizationRma = '025680800'; vehicleRegNumber = '0114TJ01'; driverRma = '461930031'; route = 'blocking-test' }

Write-Output '=== БЛОКИРУЮЩИЕ ПРОВЕРКИ: категория ВУ + непрерывность одометра ==='

# --- 1. Категория ВУ (автобус=тип1 требует D) ---
CleanVeh '0114TJ01'
try {
    docker exec $pg psql -U epd -d masterdata -c "UPDATE driver SET license_categories='B' WHERE rma='461930031';" | Out-Null
    $catNeg = Code "/api/v1/waybills" $create $hd
} finally {
    docker exec $pg psql -U epd -d masterdata -c "UPDATE driver SET license_categories='D' WHERE rma='461930031';" | Out-Null
}
Chk "Категория B на автобусе -> 422 (требуется D)" ($catNeg -eq 422)
CleanVeh '0114TJ01'
$catPos = PJ "/api/v1/waybills" $create $hd
Chk "Категория D на автобусе -> создан (не над-блокирует)" ($catPos.status -eq 'DRAFT')
Cancel $catPos.id

# --- 2. Непрерывность одометра ---
CleanVeh '0114TJ01'
$out = docker exec $pg psql -U epd -d masterdata -t -A -c "SELECT coalesce(odometer,0) FROM vehicle WHERE registration_number='0114TJ01';"
$last = [int](@($out) | Where-Object { $_ -match '^\s*\d+\s*$' } | Select-Object -First 1)
$w = PJ "/api/v1/waybills" $create $hd
$w = PJ "/api/v1/waybills/$($w.id)/titles/t1" @{ dispatcherRma = '333333333'; validityDays = 1 } $hd
$w = PJ "/api/v1/waybills/$($w.id)/confirm-med" @{ employeeRma = '111111111'; passed = $true; indicators = @{ pulse = 70; alcotest = 0 } } $hdoc
$w = PJ "/api/v1/waybills/$($w.id)/confirm-tech" @{ employeeRma = '222222222'; passed = $true; checklist = @{ brakes = 'OK' } } $hm
$w = PJ "/api/v1/waybills/$($w.id)/issue" @{ driverConfirmation = 'PIN' } $hd
$odoNeg = Code "/api/v1/waybills/$($w.id)/activate" @{ dispatcherRma = '333333333'; odometerExit = 1 } $hd
Chk "Одометр выезда 1 < пробег $last -> 422 (скрутка)" ($odoNeg -eq 422)
$odoNegNeg = Code "/api/v1/waybills/$($w.id)/activate" @{ dispatcherRma = '333333333'; odometerExit = -5 } $hd
Chk "Одометр выезда -5 -> 422 (неотрицательность)" ($odoNegNeg -eq 422)
$odoPos = Code "/api/v1/waybills/$($w.id)/activate" @{ dispatcherRma = '333333333'; odometerExit = $last } $hd
Chk "Одометр выезда = пробег -> активирован (200)" ($odoPos -eq 200)
Cancel $w.id

Write-Output ''
Write-Output "=== ИТОГ: PASS=$pass, FAIL=$fail ==="
if ($fail -gt 0) { exit 1 } else { Write-Output 'БЛОКИРУЮЩИЕ ПРОВЕРКИ АКТИВНЫ'; exit 0 }
