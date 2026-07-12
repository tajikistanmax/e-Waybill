# =====================================================================
# Тест конкурентности ЭПД РТ: инвариант «один действующий ПЛ на ТС/водителя»
# под реальной параллельностью (защита V7 частичных UNIQUE-индексов от TOCTOU-гонки).
# Запуск: powershell -File scripts\concurrency-test.ps1
# Требует: waybill :8082, Keycloak :8180, docker-контейнер epd-postgres,
#          сид-данные (ТС 0114TJ01, водитель 461930031, орг 025680800, диспетчер 333333333).
# =====================================================================
$ErrorActionPreference = 'Stop'
$wb = 'http://localhost:8082'; $kc = 'http://localhost:8180'; $pg = 'epd-postgres'
$pass = 0; $fail = 0
function Chk($name, $cond) {
    if ($cond) { $script:pass++; Write-Output "  [PASS] $name" }
    else { $script:fail++; Write-Output "  [FAIL] $name" }
}
$hd = @{ Authorization = "Bearer $((Invoke-RestMethod -Method Post -Uri "$kc/realms/epd/protocol/openid-connect/token" -Body 'client_id=epd-web&grant_type=password&username=dispatcher&password=dispatcher' -ContentType 'application/x-www-form-urlencoded').access_token)" }
function Cancel($obj) { Invoke-RestMethod -Method Post -Uri "$wb/api/v1/waybills/$($obj.id)/cancel" -Headers $hd -Body ([Text.Encoding]::UTF8.GetBytes((@{ reason = 'test'; actor = 'test' } | ConvertTo-Json))) -ContentType 'application/json; charset=utf-8' | Out-Null }
function DbCount($sql) {
    $out = docker exec $pg psql -U epd -d waybill -t -A -c $sql
    $line = @($out) | Where-Object { $_ -match '^\s*\d+\s*$' } | Select-Object -First 1
    if ($line) { [int]($line.Trim()) } else { -1 }
}
$openStat = "'CREATED','AWAITING_PAYMENT','PAID','READY','ISSUED','ACTIVE'"
$openLocal = @('DRAFT', 'CREATED', 'AWAITING_PAYMENT', 'PAID', 'READY', 'ISSUED', 'ACTIVE')

Write-Output '=== ТЕСТ КОНКУРЕНТНОСТИ: один действующий ПЛ на ТС/водителя ==='

# очистка действующих на тестовом ТС
$open = Invoke-RestMethod "$wb/api/v1/waybills" -Headers $hd
foreach ($o in $open) { if ($o.vehicleRegNumber -eq '0114TJ01' -and $openLocal -contains $o.status) { try { Cancel $o } catch {} } }

# N конкурентных агрегаторских create (каждый доходит до CREATED=OPEN за одну транзакцию)
$exit = (Get-Date -Format 'yyyy-MM-dd HH:mm')
$jobs = 1..8 | ForEach-Object {
    Start-Job -ScriptBlock {
        param($wb, $exit)
        $b = @{ organization_rma = '025680800'; transport_registration_number = '0114TJ01'; driver_rma = '461930031'; employee_rma = '333333333'; exit_date = $exit; distance = 50 } | ConvertTo-Json
        try { [int](Invoke-WebRequest -Method Post -Uri "$wb/api/v1/aggregator/waybills" -Body ([Text.Encoding]::UTF8.GetBytes($b)) -ContentType 'application/json; charset=utf-8' -UseBasicParsing).StatusCode }
        catch { if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode.value__ } else { -1 } }
    } -ArgumentList $wb, $exit
}
$codes = $jobs | Wait-Job | Receive-Job
$jobs | Remove-Job
Write-Output "  коды 8 конкурентных create: $($codes -join ', ')"
Start-Sleep -Seconds 1

# ИНВАРИАНТ: прямой подсчёт действующих ПЛ в БД (однозначно)
$byVeh = DbCount "SELECT count(*) FROM waybill WHERE vehicle_reg_number='0114TJ01' AND status IN ($openStat);"
$byDrv = DbCount "SELECT count(*) FROM waybill WHERE driver_rma='461930031' AND status IN ($openStat);"
Chk "Действующих ПЛ на ТС <= 1 (факт: $byVeh)" ($byVeh -le 1)
Chk "Действующих ПЛ на водителя <= 1 (факт: $byDrv)" ($byDrv -le 1)

# очистка
$open2 = Invoke-RestMethod "$wb/api/v1/waybills" -Headers $hd
foreach ($o in $open2) { if ($o.vehicleRegNumber -eq '0114TJ01' -and $openLocal -contains $o.status) { try { Cancel $o } catch {} } }

# --- Гонка confirmMed || confirmTech: без блокировки строки (findByIdForUpdate) JPA терял
# --- один из флагов medPassed/techPassed (last-writer-wins по всем колонкам) и ПЛ застревал
# --- в CREATED. С блокировкой итог детерминирован: оба флага + статус READY.
Write-Output ''
Write-Output '=== ГОНКА Т2 || Т3: параллельные медосмотр и техконтроль одного ПЛ ==='
function TokenOf($user) { (Invoke-RestMethod -Method Post -Uri "$kc/realms/epd/protocol/openid-connect/token" -Body "client_id=epd-web&grant_type=password&username=$user&password=$user" -ContentType 'application/x-www-form-urlencoded').access_token }
function PostJ($url, $obj, $h) { Invoke-RestMethod -Method Post -Uri $url -Headers $h -Body ([Text.Encoding]::UTF8.GetBytes(($obj | ConvertTo-Json -Depth 8))) -ContentType 'application/json; charset=utf-8' }
$w = PostJ "$wb/api/v1/waybills" @{ waybillType = 'WB_BUS'; organizationRma = '025680800'; vehicleRegNumber = '0114TJ01'; driverRma = '461930031'; route = 'concurrency-race' } $hd
$w = PostJ "$wb/api/v1/waybills/$($w.id)/titles/t1" @{ dispatcherRma = '333333333'; validityDays = 1 } $hd
$raceJobs = @(
    Start-Job -ScriptBlock { param($wb, $id, $tok)
        try { Invoke-RestMethod -Method Post -Uri "$wb/api/v1/waybills/$id/confirm-med" -Headers @{ Authorization = "Bearer $tok" } -Body ([Text.Encoding]::UTF8.GetBytes((@{ employeeRma = '111111111'; passed = $true } | ConvertTo-Json))) -ContentType 'application/json; charset=utf-8' | Out-Null; 200 } catch { -1 }
    } -ArgumentList $wb, $w.id, (TokenOf 'doctor')
    Start-Job -ScriptBlock { param($wb, $id, $tok)
        try { Invoke-RestMethod -Method Post -Uri "$wb/api/v1/waybills/$id/confirm-tech" -Headers @{ Authorization = "Bearer $tok" } -Body ([Text.Encoding]::UTF8.GetBytes((@{ employeeRma = '222222222'; passed = $true } | ConvertTo-Json))) -ContentType 'application/json; charset=utf-8' | Out-Null; 200 } catch { -1 }
    } -ArgumentList $wb, $w.id, (TokenOf 'mechanic')
)
$raceJobs | Wait-Job | Out-Null; $raceJobs | Remove-Job
Start-Sleep -Seconds 1
$after = Invoke-RestMethod "$wb/api/v1/waybills/$($w.id)" -Headers $hd
Chk "Оба осмотра учтены (med+tech, без lost update)" ($after.medPassed -and $after.techPassed)
Chk "Статус READY после гонки (факт: $($after.status))" ($after.status -eq 'READY')
try { Cancel $after } catch {}

Write-Output ''
Write-Output "=== ИТОГ: PASS=$pass, FAIL=$fail ==="
if ($fail -gt 0) { exit 1 } else { Write-Output 'ИНВАРИАНТ ДЕРЖИТСЯ ПОД КОНКУРЕНТНОСТЬЮ'; exit 0 }
