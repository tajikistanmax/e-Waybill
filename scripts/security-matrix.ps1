# =====================================================================
# Матрица авторизации ЭПД РТ: роль × чувствительный эндпоинт → ожидаемый исход.
# Постоянный регресс-щит для мультиарендности и RBAC (находки ревью 11.07).
# Запуск: powershell -File scripts\security-matrix.ps1
# Требует: master-data :8081, waybill :8082, Keycloak :8180 (realm epd),
#          пользователи admin/dispatcher/doctor/mechanic/accountant/driver/inspector.
# =====================================================================
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\demo-credentials.ps1"
$md = 'http://localhost:8081'; $wb = 'http://localhost:8082'; $kc = 'http://localhost:8180'
$pass = 0; $fail = 0

function Chk($name, $cond) {
    if ($cond) { $script:pass++; Write-Output "  [PASS] $name" }
    else { $script:fail++; Write-Output "  [FAIL] $name" }
}

$tokens = @{}
function Hdr($user) {
    if (-not $tokens.ContainsKey($user)) {
        $tokens[$user] = Get-PlatformToken $user
    }
    return @{ Authorization = "Bearer $($tokens[$user])" }
}

# HTTP-статус запроса (или -1). 200/201/204 = доступ; 401/403 = отказ; 404 = скрыто/нет.
function Status($method, $url, $headers, $bodyObj) {
    try {
        $p = @{ Method = $method; Uri = $url; Headers = $headers; TimeoutSec = 10; UseBasicParsing = $true }
        if ($null -ne $bodyObj) {
            $p.Body = [Text.Encoding]::UTF8.GetBytes(($bodyObj | ConvertTo-Json -Depth 6))
            $p.ContentType = 'application/json; charset=utf-8'
        }
        $r = Invoke-WebRequest @p
        return [int]$r.StatusCode
    } catch {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode.value__ }
        return -1
    }
}
$allowed = { param($s) $s -ge 200 -and $s -lt 300 }
$forbidden = { param($s) $s -eq 403 }

Write-Output '=== МАТРИЦА АВТОРИЗАЦИИ ЭПД РТ ==='

# --- Neru /active-by-plate: только INSPECTOR/API_INTEGRATOR/SYSTEM_ADMIN/MINTRANS_ANALYST ---
$nu = "$wb/api/v1/neru/active-by-plate?plate=0114TJ01"
Chk 'Neru: admin — доступ (не 403)'      ((Status GET $nu (Hdr 'admin-automation') $null) -ne 403)
Chk 'Neru: inspector — доступ (не 403)'  ((Status GET $nu (Hdr 'inspector-automation') $null) -ne 403)
Chk 'Neru: dispatcher — 403'             (& $forbidden (Status GET $nu (Hdr 'dispatcher') $null))
Chk 'Neru: doctor — 403'                 (& $forbidden (Status GET $nu (Hdr 'doctor') $null))
Chk 'Neru: driver — 403'                 (& $forbidden (Status GET $nu (Hdr 'driver') $null))

# --- GPS приём: только API_INTEGRATOR/SYSTEM_ADMIN ---
$gp = "$wb/api/v1/gps"; $ping = @{ vehicleRegNumber = '0114TJ01'; lat = 38.5; lon = 68.7 }
Chk 'GPS ingest: admin — доступ'         (& $allowed (Status POST $gp (Hdr 'admin-automation') $ping))
Chk 'GPS ingest: dispatcher — 403'       (& $forbidden (Status POST $gp (Hdr 'dispatcher') $ping))
Chk 'GPS ingest: inspector — 403'        (& $forbidden (Status POST $gp (Hdr 'inspector-automation') $ping))

# --- GPS чтение /last: тенант — только своя орг ---
Chk 'GPS last: dispatcher своё ТС — доступ'      (& $allowed (Status GET "$wb/api/v1/gps/last?vehicleRegNumber=0114TJ01" (Hdr 'dispatcher') $null))
Chk 'GPS last: dispatcher чужое/нет ТС — 404'    ((Status GET "$wb/api/v1/gps/last?vehicleRegNumber=ZZ9999XX" (Hdr 'dispatcher') $null) -eq 404)
Chk 'GPS last: admin (платформа) — доступ'       (& $allowed (Status GET "$wb/api/v1/gps/last?vehicleRegNumber=0114TJ01" (Hdr 'admin-automation') $null))

# --- master-data PATCH одометра: только API_INTEGRATOR/SYSTEM_ADMIN ---
$odo = "$md/api/v1/vehicles/00000000-0000-0000-0000-000000000000/odometer"
$odoBody = @{ odometer = 999999 }
Chk 'Одометр PATCH: dispatcher — 403'    (& $forbidden (Status PATCH $odo (Hdr 'dispatcher') $odoBody))
Chk 'Одометр PATCH: admin — не 403 (404 из-за id ок)' ((Status PATCH $odo (Hdr 'admin-automation') $odoBody) -ne 403)

# --- master-data GET /audit: только SYSTEM_ADMIN ---
$auditUrl = "$md/api/v1/audit?limit=1"
Chk 'Аудит: admin — доступ'              (& $allowed (Status GET $auditUrl (Hdr 'admin-automation') $null))
Chk 'Аудит: dispatcher — 403'            (& $forbidden (Status GET $auditUrl (Hdr 'dispatcher') $null))
Chk 'Аудит: inspector — 403'             (& $forbidden (Status GET $auditUrl (Hdr 'inspector-automation') $null))

# --- master-data POST конфигурации (field-definitions/classifiers): SYSTEM_ADMIN ---
$fdUrl = "$md/api/v1/field-definitions"
$fdAdmin = @{ waybillType = 'WB_SPECIAL'; fieldKey = 'secTest'; labelRu = 'т'; dataType = 'STRING' }
$fdDisp = @{ waybillType = 'WB_SPECIAL'; fieldKey = 'x'; labelRu = 'x'; dataType = 'STRING' }
$clsDisp = @{ category = 'COUNTRY'; code = 'XX'; nameRu = 'X' }
Chk 'Field-def POST: admin — доступ'     (& $allowed (Status POST $fdUrl (Hdr 'admin-automation') $fdAdmin))
Chk 'Field-def POST: dispatcher — 403'   (& $forbidden (Status POST $fdUrl (Hdr 'dispatcher') $fdDisp))
Chk 'Classifier POST: dispatcher — 403'  (& $forbidden (Status POST "$md/api/v1/classifiers" (Hdr 'dispatcher') $clsDisp))

# --- master-data GET /document-expiry: любой авторизованный (тенант-скоуп), анонимно 401 ---
Chk 'Expiry: dispatcher — доступ'        (& $allowed (Status GET "$md/api/v1/document-expiry?days=365" (Hdr 'dispatcher') $null))
Chk 'Expiry: анонимно — 401'             ((Status GET "$md/api/v1/document-expiry?days=365" @{} $null) -eq 401)

# --- master-data анонимный GET справочника — 401 ---
Chk 'Master-data GET vehicles: анонимно — 401' ((Status GET "$md/api/v1/vehicles" @{} $null) -eq 401)

# --- waybill create: врач не создаёт ПЛ (403) ---
$cr = @{ waybillType = 'WB_BUS'; organizationRma = '025680800'; vehicleRegNumber = '0114TJ01'; driverRma = '461930031' }
Chk 'Create ПЛ: doctor — 403'            (& $forbidden (Status POST "$wb/api/v1/waybills" (Hdr 'doctor') $cr))

# --- Удаление документа субъекта: DISPATCHER больше нельзя (только SYSTEM_ADMIN/COMPANY_ADMIN) ---
$docDel = "$md/api/v1/vehicles/0114TJ01/documents/00000000-0000-0000-0000-000000000000"
Chk 'SubjectDoc delete: dispatcher — 403'                 (& $forbidden (Status DELETE $docDel (Hdr 'dispatcher') $null))
Chk 'SubjectDoc delete: admin — не 403 (404 из-за id ок)' ((Status DELETE $docDel (Hdr 'admin-automation') $null) -ne 403)

# --- E-PERMIT просмотр дозвола /sync/permit: закрыт open-by-default (sync-роли — да, прочие — 403) ---
$permit = "$md/api/v1/sync/permit/TEST-000"
Chk 'Permit: dispatcher — не 403 (sync-роль)' ((Status GET $permit (Hdr 'dispatcher') $null) -ne 403)
Chk 'Permit: doctor — 403'                    (& $forbidden (Status GET $permit (Hdr 'doctor') $null))

# --- «Пользователи» платформы: только SYSTEM_ADMIN (24.09) ---
$pu = "$md/api/v1/platform-users?size=1"
Chk 'Platform users: admin — доступ'         (& $allowed (Status GET $pu (Hdr 'admin-automation') $null))
Chk 'Platform users: company admin — 403'   (& $forbidden (Status GET $pu (Hdr 'company') $null))
Chk 'Platform users: analyst — 403'         (& $forbidden (Status GET $pu (Hdr 'analyst-automation') $null))
Chk 'Platform users: dispatcher — 403'      (& $forbidden (Status GET $pu (Hdr 'dispatcher') $null))

# --- Изображения для бланка: подпись — любой печатающий ПЛ; паспорт — только ведущим документы (24.09) ---
$sig = "$md/api/v1/employees/111111111/documents/latest?docType=SIGNATURE"
$pas = "$md/api/v1/employees/111111111/documents/latest?docType=PASSPORT"
Chk 'Подпись сотрудника: inspector — не 403'  ((Status GET $sig (Hdr 'inspector-automation') $null) -ne 403)
Chk 'Паспорт сотрудника: inspector — 403'     (& $forbidden (Status GET $pas (Hdr 'inspector-automation') $null))
Chk 'Паспорт сотрудника: doctor — 403'        (& $forbidden (Status GET $pas (Hdr 'doctor') $null))

# --- Мультиарендность ПЛ: чужой по id → 404, свой → доступ (нужен dispatcher2, org 990000001) ---
try {
    $hdM = Hdr 'dispatcher'
    $mine = Invoke-RestMethod "$wb/api/v1/waybills" -Headers $hdM
    $myId = if ($mine.Count -gt 0) { $mine[0].id } else { $null }
    $t2ok = $false
    try { Hdr 'dispatcher2' | Out-Null; $t2ok = $true } catch { $t2ok = $false }
    if ($myId -and $t2ok) {
        Chk 'Мультиарендность: dispatcher свой ПЛ по id — доступ'    (& $allowed (Status GET "$wb/api/v1/waybills/$myId" $hdM $null))
        Chk 'Мультиарендность: dispatcher2 чужой ПЛ по id — 404'     ((Status GET "$wb/api/v1/waybills/$myId" (Hdr 'dispatcher2') $null) -eq 404)
        Chk 'Мультиарендность: dispatcher2 ТС — своя орг (не 401/403)' (& $allowed (Status GET "$md/api/v1/vehicles" (Hdr 'dispatcher2') $null))
    } else {
        Write-Output "  [SKIP] Кросс-тенант ПЛ (нет dispatcher2 или ПЛ)"
    }
} catch { Write-Output "  [SKIP] Кросс-тенант ПЛ: $($_.Exception.Message)" }

# --- Очистка тестовой конфигурации (идемпотентность) ---
try {
    $ha = Hdr 'admin-automation'
    # ${fdUrl}: в "$fdUrl?..." PowerShell считал «?» частью имени переменной — URL был пустым,
    # уборка молча не работала, и поле secTest («т») оставалось в мастере спецтехники.
    $defs = Invoke-RestMethod "${fdUrl}?waybillType=WB_SPECIAL&all=true" -Headers $ha
    foreach ($d in $defs) { if ($d.fieldKey -eq 'secTest') { Invoke-RestMethod -Method Delete -Uri "$fdUrl/$($d.id)" -Headers $ha | Out-Null } }
} catch {}

Write-Output ''
Write-Output "=== ИТОГ МАТРИЦЫ: PASS=$pass, FAIL=$fail ==="
if ($fail -gt 0) { exit 1 } else { Write-Output 'ВСЕ ГРАНИЦЫ АВТОРИЗАЦИИ КОРРЕКТНЫ'; exit 0 }
