# =====================================================================
# Демо-данные: несколько компаний-перевозчиков с ТС, водителями и сотрудниками.
# Регистрация идёт штатным путём /api/v1/sync/* (данные из dev-заглушки единой
# платформы — реалистичные таджикские имена). Идемпотентно (sync = upsert).
# Запуск: powershell -File scripts\seed-demo-data.ps1
# Требует: master-data :8081, Keycloak :8180, admin/admin. Реальный API — потом.
# =====================================================================
$ErrorActionPreference = 'Stop'
$md = 'http://localhost:8081'; $kc = 'http://localhost:8180'
$tok = (Invoke-RestMethod -Method Post -Uri "$kc/realms/epd/protocol/openid-connect/token" -Body 'client_id=epd-web&grant_type=password&username=admin&password=admin' -ContentType 'application/x-www-form-urlencoded').access_token
$hd = @{ Authorization = "Bearer $tok" }
$ok = 0; $fail = 0
function Sync($path, $body) {
    try {
        $r = Invoke-WebRequest -Method Post -Uri "$md$path" -Headers $hd -Body ([Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json))) -ContentType 'application/json; charset=utf-8' -UseBasicParsing
        $script:ok++; return $true
    } catch {
        $script:fail++
        $code = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode.value__ } else { -1 }
        Write-Output "    [FAIL $code] $path $($body | ConvertTo-Json -Compress)"
        return $false
    }
}
# 9-значный ИНН: 8-значная база (кодирует компанию) + управляемая последняя цифра
# (тип субъекта в заглушке: 0-5 юрлицо, 6-7 ИП, 8-9 физлицо).
function MkInn($seed, $last) { ("{0:D8}" -f $seed).Substring(0, 8) + "$last" }

$COMPANIES = 1..4
Write-Output "=== СИД ДЕМО-ДАННЫХ: $($COMPANIES.Count) компаний ==="
foreach ($c in $COMPANIES) {
    $orgInn = MkInn (10000000 + $c * 100) 0   # юрлицо-перевозчик
    Write-Output "Компания $c (ИНН $orgInn):"
    if (Sync '/api/v1/sync/organization' @{ inn = $orgInn }) { Write-Output "  организация зарегистрирована" }
    # транспорт (3 ед.)
    $nv = 0
    foreach ($v in 1..3) { $plate = ('{0}{1}00TJ0{0}' -f $c, $v); if (Sync '/api/v1/sync/vehicle' @{ registrationNumber = $plate; organizationRma = $orgInn }) { $nv++ } }
    Write-Output "  транспорт: $nv"
    # водители (4)
    $nd = 0
    foreach ($d in 1..4) { $dinn = MkInn (20000000 + $c * 100 + $d) 8; if (Sync '/api/v1/sync/driver' @{ inn = $dinn; organizationRma = $orgInn }) { $nd++ } }
    Write-Output "  водители: $nd"
    # сотрудники: 1=врач, 2=механик, 3=диспетчер
    $ne = 0
    foreach ($role in 1..3) { $einn = MkInn (30000000 + $c * 100 + $role) 9; if (Sync '/api/v1/sync/employee' @{ inn = $einn; organizationRma = $orgInn; type = [int]$role }) { $ne++ } }
    Write-Output "  сотрудники (врач/механик/диспетчер): $ne"
}

Write-Output ''
Write-Output "=== ИТОГ: успешно=$ok, ошибок=$fail ==="
Write-Output "Организаций в реплике:"
docker exec epd-postgres psql -U epd -d masterdata -t -A -F ' | ' -c "SELECT rma, name FROM organization ORDER BY rma;"
docker exec epd-postgres psql -U epd -d masterdata -t -A -F '|' -c "SELECT 'ТС всего',count(*) FROM vehicle UNION ALL SELECT 'Водителей',count(*) FROM driver UNION ALL SELECT 'Сотрудников',count(*) FROM employee;"
if ($fail -gt 0) { exit 1 } else { exit 0 }
