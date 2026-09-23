# =====================================================================
# Готовый набор edge-case данных для негативных тестов (QA §26, spec/QA-статус-Test1.md).
# Создаёт ОДНУ выделенную тестовую организацию с явно поименованными фикстурами
# (истёкшие документы, заблокированные/отстранённые водитель/ТС/организация),
# чтобы их можно было руками выбрать в интерфейсе или использовать в regress-скриптах,
# не путая с реальными демо-данными. Идемпотентен — можно перезапускать.
# Запуск: powershell -File scripts\seed-edge-cases.ps1
# Требует: master-data :8081, Keycloak :8180 (realm epd)
# =====================================================================
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\demo-credentials.ps1"
$md = "http://localhost:8081"
$kc = "http://localhost:8180"

function GetToken($user) {
    Get-PlatformToken $user
}
function PostJson($url, $obj, $headers) {
    $json = $obj | ConvertTo-Json -Depth 8
    Invoke-RestMethod -Method Post -Uri $url -Headers $headers -Body ([Text.Encoding]::UTF8.GetBytes($json)) -ContentType 'application/json; charset=utf-8'
}

$ha = @{ Authorization = "Bearer $(GetToken 'admin-automation')" }  # SYSTEM_ADMIN — нужен для blocked/suspended полей
$past = (Get-Date).AddYears(-1).ToString("yyyy-MM-dd")
$farFuture = (Get-Date).AddYears(2).ToString("yyyy-MM-dd")
$nearFuture = (Get-Date).AddMonths(6).ToString("yyyy-MM-dd")

Write-Output "=== СИД EDGE-CASE ДАННЫХ ДЛЯ НЕГАТИВНЫХ ТЕСТОВ ==="

# --- Выделенная тестовая организация (активная — сама блокировка орг. тестируется отдельно ниже) ---
$orgRma = "090000001"
$org = PostJson "$md/api/v1/organizations" @{
    rma = $orgRma; name = "ЭДЖ-КЕЙС: Тестовый перевозчик (не удалять)"; typeCompany = 1; regionId = 1
    licenseFrom = "2025-01-01"; licenseTo = $farFuture
} $ha
Write-Output "Организация: $($org.name) ($orgRma)"

# --- Водители: явно поименованные, чтобы было видно в списке, какой edge-case проверяет ---
$drivers = @(
    @{ rma = "090100001"; name = "ЭДЖ-КЕЙС: истёкшие права"; licenseValidTo = $past; medCertValidTo = $nearFuture; cat = "D" },
    @{ rma = "090100002"; name = "ЭДЖ-КЕЙС: истёкшая медсправка"; licenseValidTo = $farFuture; medCertValidTo = $past; cat = "D" },
    @{ rma = "090100003"; name = "ЭДЖ-КЕЙС: категория B (не хватает D для автобуса)"; licenseValidTo = $farFuture; medCertValidTo = $nearFuture; cat = "B" },
    @{ rma = "090100004"; name = "ЭДЖ-КЕЙС: отстранён (suspended)"; licenseValidTo = $farFuture; medCertValidTo = $nearFuture; cat = "D"; suspend = $true }
)
foreach ($d in $drivers) {
    $body = @{
        rma = $d.rma; organizationRma = $orgRma; fullName = $d.name
        licenseNumber = "ED" + $d.rma.Substring(3); licenseCategories = $d.cat
        licenseValidTo = $d.licenseValidTo; medCertValidTo = $d.medCertValidTo
    }
    if ($d.suspend) { $body.suspended = $true }
    $r = PostJson "$md/api/v1/drivers" $body $ha
    Write-Output "Водитель: $($r.fullName) ($($r.rma)) suspended=$($r.suspended)"
}

# --- ТС: без страховки / без техосмотра / заблокировано ---
$vehicles = @(
    @{ reg = "EDGE001TJ"; name = "ЭДЖ-КЕЙС: без страховки"; insuranceValidTo = $past; techValidTo = $nearFuture },
    @{ reg = "EDGE002TJ"; name = "ЭДЖ-КЕЙС: без техосмотра"; insuranceValidTo = $nearFuture; techValidTo = $past },
    @{ reg = "EDGE003TJ"; name = "ЭДЖ-КЕЙС: заблокировано Минтрансом"; insuranceValidTo = $nearFuture; techValidTo = $nearFuture; blocked = $true }
)
foreach ($v in $vehicles) {
    $body = @{
        registrationNumber = $v.reg; organizationRma = $orgRma; transportType = 1; brand = "Акиа (эдж-кейс)"
        techInspectionValidTo = $v.techValidTo; controlCardValidTo = $nearFuture; insuranceValidTo = $v.insuranceValidTo
    }
    if ($v.blocked) { $body.blocked = $true }
    $r = PostJson "$md/api/v1/vehicles" $body $ha
    Write-Output "ТС: $($v.name) ($($r.registrationNumber)) blocked=$($r.blocked)"
}

Write-Output ""
Write-Output "=== ГОТОВО. Все фикстуры организации $orgRma поименованы «ЭДЖ-КЕЙС: ...» ==="
Write-Output "Отдельно (не создаётся здесь, т.к. ломает саму организацию для дальнейших тестов):"
Write-Output "  заблокировать организацию — POST /api/v1/organizations { rma: '$orgRma', blocked: true } под admin"
