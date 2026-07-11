# =====================================================================
# Матрица авторизации ЭПД РТ: роль × чувствительный эндпоинт → ожидаемый исход.
# Постоянный регресс-щит для мультиарендности и RBAC (находки ревью 11.07).
# Запуск: powershell -File scripts\security-matrix.ps1
# Требует: master-data :8081, waybill :8082, Keycloak :8180 (realm epd),
#          пользователи admin/dispatcher/doctor/mechanic/accountant/driver/inspector.
# =====================================================================
$ErrorActionPreference = 'Stop'
$md = 'http://localhost:8081'; $wb = 'http://localhost:8082'; $kc = 'http://localhost:8180'
$pass = 0; $fail = 0

function Chk($name, $cond) {
    if ($cond) { $script:pass++; Write-Output "  [PASS] $name" }
    else { $script:fail++; Write-Output "  [FAIL] $name" }
}

$tokens = @{}
function Hdr($user) {
    if (-not $tokens.ContainsKey($user)) {
        $body = "client_id=epd-web&grant_type=password&username=$user&password=$user"
        $tokens[$user] = (Invoke-RestMethod -Method Post -Uri "$kc/realms/epd/protocol/openid-connect/token" -Body $body -ContentType 'application/x-www-form-urlencoded').access_token
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
Chk 'Neru: admin — доступ (не 403)'      ((Status GET $nu (Hdr 'admin') $null) -ne 403)
Chk 'Neru: inspector — доступ (не 403)'  ((Status GET $nu (Hdr 'inspector') $null) -ne 403)
Chk 'Neru: dispatcher — 403'             (& $forbidden (Status GET $nu (Hdr 'dispatcher') $null))
Chk 'Neru: doctor — 403'                 (& $forbidden (Status GET $nu (Hdr 'doctor') $null))
Chk 'Neru: driver — 403'                 (& $forbidden (Status GET $nu (Hdr 'driver') $null))

# --- GPS приём: только API_INTEGRATOR/SYSTEM_ADMIN ---
$gp = "$wb/api/v1/gps"; $ping = @{ vehicleRegNumber = '0114TJ01'; lat = 38.5; lon = 68.7 }
Chk 'GPS ingest: admin — доступ'         (& $allowed (Status POST $gp (Hdr 'admin') $ping))
Chk 'GPS ingest: dispatcher — 403'       (& $forbidden (Status POST $gp (Hdr 'dispatcher') $ping))
Chk 'GPS ingest: inspector — 403'        (& $forbidden (Status POST $gp (Hdr 'inspector') $ping))

# --- GPS чтение /last: тенант — только своя орг ---
Chk 'GPS last: dispatcher своё ТС — доступ'      (& $allowed (Status GET "$wb/api/v1/gps/last?vehicleRegNumber=0114TJ01" (Hdr 'dispatcher') $null))
Chk 'GPS last: dispatcher чужое/нет ТС — 404'    ((Status GET "$wb/api/v1/gps/last?vehicleRegNumber=ZZ9999XX" (Hdr 'dispatcher') $null) -eq 404)
Chk 'GPS last: admin (платформа) — доступ'       (& $allowed (Status GET "$wb/api/v1/gps/last?vehicleRegNumber=0114TJ01" (Hdr 'admin') $null))

# --- master-data PATCH одометра: только API_INTEGRATOR/SYSTEM_ADMIN ---
$odo = "$md/api/v1/vehicles/00000000-0000-0000-0000-000000000000/odometer"
$odoBody = @{ odometer = 999999 }
Chk 'Одометр PATCH: dispatcher — 403'    (& $forbidden (Status PATCH $odo (Hdr 'dispatcher') $odoBody))
Chk 'Одометр PATCH: admin — не 403 (404 из-за id ок)' ((Status PATCH $odo (Hdr 'admin') $odoBody) -ne 403)

# --- master-data GET /audit: только SYSTEM_ADMIN ---
$auditUrl = "$md/api/v1/audit?limit=1"
Chk 'Аудит: admin — доступ'              (& $allowed (Status GET $auditUrl (Hdr 'admin') $null))
Chk 'Аудит: dispatcher — 403'            (& $forbidden (Status GET $auditUrl (Hdr 'dispatcher') $null))
Chk 'Аудит: inspector — 403'             (& $forbidden (Status GET $auditUrl (Hdr 'inspector') $null))

# --- master-data POST конфигурации (field-definitions/classifiers): SYSTEM_ADMIN ---
$fdUrl = "$md/api/v1/field-definitions"
$fdAdmin = @{ waybillType = 'WB_SPECIAL'; fieldKey = 'secTest'; labelRu = 'т'; dataType = 'STRING' }
$fdDisp = @{ waybillType = 'WB_SPECIAL'; fieldKey = 'x'; labelRu = 'x'; dataType = 'STRING' }
$clsDisp = @{ category = 'COUNTRY'; code = 'XX'; nameRu = 'X' }
Chk 'Field-def POST: admin — доступ'     (& $allowed (Status POST $fdUrl (Hdr 'admin') $fdAdmin))
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

# --- Очистка тестовой конфигурации (идемпотентность) ---
try {
    $ha = Hdr 'admin'
    $defs = Invoke-RestMethod "$fdUrl?waybillType=WB_SPECIAL&all=true" -Headers $ha
    foreach ($d in $defs) { if ($d.fieldKey -eq 'secTest') { Invoke-RestMethod -Method Delete -Uri "$fdUrl/$($d.id)" -Headers $ha | Out-Null } }
} catch {}

Write-Output ''
Write-Output "=== ИТОГ МАТРИЦЫ: PASS=$pass, FAIL=$fail ==="
if ($fail -gt 0) { exit 1 } else { Write-Output 'ВСЕ ГРАНИЦЫ АВТОРИЗАЦИИ КОРРЕКТНЫ'; exit 0 }
