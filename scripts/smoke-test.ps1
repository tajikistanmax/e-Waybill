# =====================================================================
# Приёмочный smoke-тест платформы ЭПД РТ (раздел 15 ТЗ)
# Прогоняет ключевые сценарии против запущенного стека.
# Запуск: powershell -File scripts\smoke-test.ps1
# Требует: master-data :8081, waybill :8082, Keycloak :8180 (realm epd)
# =====================================================================
$ErrorActionPreference = 'Stop'
$md = "http://localhost:8081"
$wb = "http://localhost:8082"
$kc = "http://localhost:8180"
$pass = 0; $fail = 0

function Check($name, $ok) {
    if ($ok) { $script:pass++; Write-Output "  [PASS] $name" }
    else { $script:fail++; Write-Output "  [FAIL] $name" }
}

function GetToken($user) {
    $body = "client_id=epd-web&grant_type=password&username=$user&password=epd123"
    (Invoke-RestMethod -Method Post -Uri "$kc/realms/epd/protocol/openid-connect/token" -Body $body -ContentType "application/x-www-form-urlencoded").access_token
}

function PostJson($url, $obj, $headers) {
    $json = $obj | ConvertTo-Json -Depth 8
    Invoke-RestMethod -Method Post -Uri $url -Headers $headers -Body ([Text.Encoding]::UTF8.GetBytes($json)) -ContentType 'application/json; charset=utf-8'
}

Write-Output "=== SMOKE-ТЕСТ ПЛАТФОРМЫ ЭПД РТ ==="

# --- 0. Токены и здоровье ---
$hd = @{ Authorization = "Bearer $(GetToken 'dispatcher@epd.tj')" }  # диспетчер
$hdoc = @{ Authorization = "Bearer $(GetToken 'doctor@epd.tj')" }     # врач
$hm = @{ Authorization = "Bearer $(GetToken 'mechanic@epd.tj')" }    # механик
$ha = @{ Authorization = "Bearer $(GetToken 'admin@epd.tj')" }       # админ компании
Check "Токены всех ролей получены" ($hd -and $hdoc -and $hm -and $ha)
Check "master-data health UP" ((Invoke-RestMethod "$md/actuator/health").status -eq 'UP')
Check "waybill health UP" ((Invoke-RestMethod "$wb/actuator/health").status -eq 'UP')

# --- 1. Безопасность ---
try { Invoke-RestMethod "$wb/api/v1/waybills" | Out-Null; Check "401 без токена" $false }
catch { Check "401 без токена" ($_.Exception.Response.StatusCode.value__ -eq 401) }
try {
    PostJson "$wb/api/v1/waybills" @{ waybillType = "WB_BUS"; organizationRma = "025680800"; vehicleRegNumber = "0114TJ01"; driverRma = "461930031" } $hdoc | Out-Null
    Check "403: врач не создаёт ПЛ" $false
} catch { Check "403: врач не создаёт ПЛ" ($_.Exception.Response.StatusCode.value__ -eq 403) }

# --- 2. Мастер-данные (upsert идемпотентен) ---
$org = PostJson "$md/api/v1/organizations" @{ rma = "025680800"; name = "КВД Автобуси Душанбе"; typeCompany = 1; regionId = 1; licenseFrom = "2025-01-01"; licenseTo = "2027-12-31" } $ha
Check "Организация upsert" ($org.rma -eq "025680800")
$drv = PostJson "$md/api/v1/drivers" @{ rma = "461930031"; organizationRma = "025680800"; fullName = "Ахмедзода Зохид"; licenseNumber = "AB1234567"; licenseCategories = "D"; licenseValidTo = "2028-05-01"; medCertValidTo = "2026-12-31" } $ha
Check "Водитель upsert" ($drv.rma -eq "461930031")
$veh = PostJson "$md/api/v1/vehicles" @{ registrationNumber = "0114TJ01"; organizationRma = "025680800"; transportType = 1; brand = "Акиа"; techInspectionValidTo = "2026-10-01"; controlCardValidTo = "2026-09-01" } $ha
Check "ТС upsert" ($veh.registrationNumber -eq "0114TJ01")
foreach ($e in @(@{rma="111111111";n="Раҳимова С.";t=1}, @{rma="222222222";n="Қосимов Ф.";t=2}, @{rma="333333333";n="Назарова М.";t=3})) {
    PostJson "$md/api/v1/employees" @{ rma = $e.rma; organizationRma = "025680800"; name = $e.n; type = $e.t } $ha | Out-Null
}
Check "Сотрудники upsert (врач/механик/диспетчер)" $true

# --- 3. Очистка: аннулировать незакрытые ПЛ тестового ТС ---
$open = Invoke-RestMethod "$wb/api/v1/waybills" -Headers $hd
$openStatuses = @('DRAFT','CREATED','AWAITING_PAYMENT','READY','ISSUED','ACTIVE')
foreach ($o in $open) {
    if ($o.vehicleRegNumber -eq '0114TJ01' -and $openStatuses -contains $o.status) {
        try { PostJson "$wb/api/v1/waybills/$($o.id)/cancel" @{ reason = "smoke-очистка"; actor = "smoke" } $hd | Out-Null } catch {}
    }
}
Check "Очистка действующих ПЛ" $true

# --- 4. Полный жизненный цикл ---
$w = PostJson "$wb/api/v1/waybills" @{ waybillType = "WB_BUS"; organizationRma = "025680800"; vehicleRegNumber = "0114TJ01"; driverRma = "461930031"; communicationType = "URBAN"; route = "Smoke-маршрут" } $hd
Check "Создание ПЛ (DRAFT)" ($w.status -eq 'DRAFT')
$w = PostJson "$wb/api/v1/waybills/$($w.id)/titles/t1" @{ dispatcherRma = "333333333"; validityDays = 1 } $hd
Check "Т1 диспетчером (CREATED)" ($w.status -eq 'CREATED')
$w = PostJson "$wb/api/v1/waybills/$($w.id)/confirm-med" @{ employeeRma = "111111111"; passed = $true; indicators = @{ pulse = 70; alcotest = 0 } } $hdoc
Check "Т2 врачом" $w.medPassed
$w = PostJson "$wb/api/v1/waybills/$($w.id)/confirm-tech" @{ employeeRma = "222222222"; passed = $true; checklist = @{ brakes = "OK" } } $hm
Check "Т3 механиком → READY + номер" (($w.status -eq 'READY') -and $w.number)
Check "Формат номера (Луна)" ($w.number -match '^\d{2}-\d{2}-\d{2}-\d{7}-\d$')
$qr = Invoke-RestMethod "$wb/api/v1/waybills/$($w.id)/qr" -Headers $hd
Check "QR JWS выдан" ($qr.jws.Length -gt 100)
$w = PostJson "$wb/api/v1/waybills/$($w.id)/issue" @{ driverConfirmation = "PIN" } $hd
$w = PostJson "$wb/api/v1/waybills/$($w.id)/activate" @{ dispatcherRma = "333333333" } $hd
Check "Выдан и активирован (Т4)" ($w.status -eq 'ACTIVE')

# Негатив: второй ПЛ на то же ТС
try {
    PostJson "$wb/api/v1/waybills" @{ waybillType = "WB_BUS"; organizationRma = "025680800"; vehicleRegNumber = "0114TJ01"; driverRma = "461930031" } $hd | Out-Null
    Check "409: один активный ПЛ на ТС" $false
} catch { Check "409: один активный ПЛ на ТС" ($_.Exception.Response.StatusCode.value__ -eq 409) }

$exit = $w.odometerExit
$w = PostJson "$wb/api/v1/waybills/$($w.id)/return" @{ dispatcherRma = "333333333"; odometerEntry = ($exit + 120) } $hd
Check "Т5 возврат (+120 км)" ($w.status -eq 'RETURNED')
PostJson "$wb/api/v1/waybills/$($w.id)/confirm-med" @{ employeeRma = "111111111"; passed = $true } $hdoc | Out-Null
$w = PostJson "$wb/api/v1/waybills/$($w.id)/close" @{ actor = "333333333" } $hd
Check "Т6 + закрытие (COMPLETED)" ($w.status -eq 'COMPLETED')

# --- 5. Проверка QR (публичная) ---
$v = Invoke-RestMethod "$wb/api/v1/verify/$($qr.jws)"
Check "Публичная проверка QR: подпись валидна" $v.signatureValid

# --- 6. Расчёт топлива ---
$fc = Invoke-RestMethod "$wb/api/v1/waybills/$($w.id)/fuel-calculation" -Headers $hd
Check "Нормирование топлива (норма > 0)" ([decimal]$fc.normLiters -gt 0)

# --- 7. Международный ПЛ: валидация ---
try {
    PostJson "$wb/api/v1/waybills" @{ waybillType = "WB_TRUCK_INTL"; organizationRma = "025680800"; vehicleRegNumber = "0114TJ01"; driverRma = "461930031"; typeData = @{ cargoName = "Тест" } } $hd | Out-Null
    Check "422: международный без визы/дозвола" $false
} catch { Check "422: международный без визы/дозвола" ($_.Exception.Response.StatusCode.value__ -eq 422) }

# --- 8. API агрегаторов (без токена, legacy) ---
$agg = PostJson "$wb/api/v1/aggregator/waybills" @{ organization_rma = "025680800"; transport_registration_number = "0114TJ01"; driver_rma = "461930031"; employee_rma = "333333333"; exit_date = (Get-Date -Format "yyyy-MM-dd HH:mm"); distance = 50 } @{}
Check "Агрегатор: заявка принята" ($agg.id)
try { Invoke-RestMethod "$wb/api/v1/aggregator/waybills/$($agg.id)" | Out-Null; Check "Агрегатор: 409 до осмотров" $false }
catch { Check "Агрегатор: 409 до осмотров" ($_.Exception.Response.StatusCode.value__ -eq 409) }
PostJson "$wb/api/v1/waybills/$($agg.id)/cancel" @{ reason = "smoke-очистка"; actor = "smoke" } $hd | Out-Null

# --- 9. Отчёты ---
$to = Get-Date -Format "yyyy-MM-dd"; $from = (Get-Date).AddDays(-30).ToString("yyyy-MM-dd")
$sum = Invoke-RestMethod "$wb/api/v1/reports/summary?from=$from&to=$to" -Headers $hd
Check "Отчёт-сводка (ПЛ > 0)" ($sum.totals.waybills -gt 0)

# --- 10. Справочники ---
$norms = Invoke-RestMethod "$md/api/v1/dictionaries/fuel-norms" -Headers $hd
Check "Справочник норм расхода (сиды)" ($norms.Count -ge 5)

Write-Output ""
Write-Output "=== ИТОГ: PASS=$pass, FAIL=$fail ==="
if ($fail -gt 0) { exit 1 } else { Write-Output "ВСЕ ПРОВЕРКИ ПРОЙДЕНЫ ✓"; exit 0 }
