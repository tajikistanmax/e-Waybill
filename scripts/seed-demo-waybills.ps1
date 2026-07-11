# =====================================================================
# Демо-путевые листы: по одному действующему ПЛ на каждую компанию через
# агрегаторский поток (авто-подбор совместимой пары водитель↔ТС по категории ВУ).
# Наполняет платформу документами для наглядной демонстрации.
# Запуск: powershell -File scripts\seed-demo-waybills.ps1  (после seed-demo-data.ps1)
# Требует: waybill :8082 (AGGREGATOR_OPEN=true), docker epd-postgres.
# =====================================================================
$ErrorActionPreference = 'Stop'
$wb = 'http://localhost:8082'; $pg = 'epd-postgres'
function ReqCat($t) { switch ([int]$t) { 1 { 'D' } 3 { 'D' } 4 { 'B' } 5 { 'C' } 6 { 'C' } default { '' } } }
function HasCat($cats, $req) { if (-not $req) { return $true }; @($cats -split '[,;\s]+' | Where-Object { $_ }) -contains $req }
function MkInn($seed, $last) { ("{0:D8}" -f $seed).Substring(0, 8) + "$last" }
$ok = 0; $fail = 0

Write-Output "=== ДЕМО-ПУТЕВЫЕ ЛИСТЫ (агрегаторский поток) ==="
foreach ($c in 1..4) {
    $org = MkInn (10000000 + $c * 100) 0
    $disp = MkInn (30000000 + $c * 100 + 3) 9   # диспетчер (type 3)
    $vraw = docker exec $pg psql -U epd -d masterdata -t -A -F '|' -c "SELECT v.registration_number, v.transport_type FROM vehicle v JOIN organization o ON v.organization_id=o.id WHERE o.rma='$org' ORDER BY v.registration_number;"
    $vehs = @($vraw | Where-Object { $_ } | ForEach-Object { $p = $_ -split '\|'; @{ plate = $p[0]; type = $p[1] } })
    $draw = docker exec $pg psql -U epd -d masterdata -t -A -F '|' -c "SELECT d.rma, d.license_categories FROM driver d JOIN organization o ON d.organization_id=o.id WHERE o.rma='$org' ORDER BY d.rma;"
    $drvs = @($draw | Where-Object { $_ } | ForEach-Object { $p = $_ -split '\|'; @{ rma = $p[0]; cats = $p[1] } })
    # совместимая пара: категория ВУ водителя подходит типу ТС
    $pair = $null
    foreach ($v in $vehs) { $req = ReqCat $v.type; foreach ($d in $drvs) { if (HasCat $d.cats $req) { $pair = @{ v = $v; d = $d }; break } }; if ($pair) { break } }
    if (-not $pair) { Write-Output "Компания $c ($org): совместимая пара не найдена"; $fail++; continue }
    $body = @{ organization_rma = $org; transport_registration_number = $pair.v.plate; driver_rma = $pair.d.rma; employee_rma = $disp; exit_date = (Get-Date -Format 'yyyy-MM-dd HH:mm'); distance = 50 } | ConvertTo-Json
    try {
        $r = Invoke-RestMethod -Method Post -Uri "$wb/api/v1/aggregator/waybills" -Body ([Text.Encoding]::UTF8.GetBytes($body)) -ContentType 'application/json; charset=utf-8'
        Write-Output "Компания ${c}: ПЛ на ТС $($pair.v.plate) (тип $($pair.v.type)) + водитель $($pair.d.rma) [$($pair.d.cats)]  id=$($r.id)"
        $ok++
    } catch {
        $code = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode.value__ } else { -1 }
        Write-Output "Компания ${c}: ошибка $code $($_.ErrorDetails.Message)"; $fail++
    }
}
Write-Output ''
Write-Output "=== ИТОГ: создано=$ok, ошибок=$fail ==="
if ($fail -gt 0) { exit 1 } else { exit 0 }
