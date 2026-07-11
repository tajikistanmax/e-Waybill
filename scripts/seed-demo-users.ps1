# =====================================================================
# Демо-пользователи по компаниям: на каждую из 4 компаний-перевозчиков —
# диспетчер, врач, механик, водитель (роль + атрибут organizationRma = РМА
# компании, атрибут rma = РМА сотрудника/водителя). Логин = имя пользователя.
# Мультиарендность: каждый видит только свою организацию. Идемпотентно.
# Запуск: powershell -File scripts\seed-demo-users.ps1  (после seed-demo-data.ps1)
# Требует: Keycloak :8180 (admin/admin_dev_password), realm epd.
# =====================================================================
$ErrorActionPreference = 'Stop'
$kc = 'http://localhost:8180'
$adm = (Invoke-RestMethod -Method Post -Uri "$kc/realms/master/protocol/openid-connect/token" -Body 'client_id=admin-cli&grant_type=password&username=admin&password=admin_dev_password' -ContentType 'application/x-www-form-urlencoded').access_token
$H = @{ Authorization = "Bearer $adm" }
$JH = @{ Authorization = "Bearer $adm"; 'Content-Type' = 'application/json' }
$roleCache = @{}
function RoleRep($name) {
    if (-not $roleCache.ContainsKey($name)) { $roleCache[$name] = Invoke-RestMethod "$kc/admin/realms/epd/roles/$name" -Headers $H }
    $roleCache[$name]
}
$ok = 0; $fail = 0
function Upsert($username, $org, $rma, $roleName, $fio) {
    try {
        # ВАЖНО: Invoke-RestMethod на пустой JSON-массив [] возвращает $null, а @($null).Count==1 —
        # поэтому существование проверяем по фактическому id, а не по .Count обёртки @().
        $found = Invoke-RestMethod "$kc/admin/realms/epd/users?username=$username&exact=true" -Headers $H
        $existingId = if ($found) { (@($found)[0]).id } else { $null }
        # emailVerified + пустой requiredActions — иначе direct-grant вход даёт
        # "Account is not fully set up" (висящее обязательное действие).
        # email обязателен: без него direct-grant вход даёт "Account is not fully set up".
        $rep = @{
            username = $username; enabled = $true; emailVerified = $true; requiredActions = @()
            email = "$username@dts.tj"; firstName = $fio; lastName = 'DTS'
            attributes = @{ organizationRma = @($org); rma = @($rma) }
        }
        if ($existingId) {
            $uid = $existingId
            Invoke-WebRequest -Method Put -Uri "$kc/admin/realms/epd/users/$uid" -Headers @{ Authorization = "Bearer $adm" } -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes(($rep | ConvertTo-Json -Depth 6))) -UseBasicParsing | Out-Null
        } else {
            # id нового юзера берём из заголовка Location (надёжно; пере-поиск сразу
            # после создания может вернуть пусто из-за индексации → пустой uid → 405).
            $resp = Invoke-WebRequest -Method Post -Uri "$kc/admin/realms/epd/users" -Headers @{ Authorization = "Bearer $adm" } -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes(($rep | ConvertTo-Json -Depth 6))) -UseBasicParsing
            $loc = $resp.Headers['Location']; if ($loc -is [array]) { $loc = $loc[0] }
            $uid = ($loc -split '/')[-1]
        }
        # Пароль — отдельным вызовом (надёжно, не временный).
        $pw = @{ type = 'password'; value = $username; temporary = $false } | ConvertTo-Json -Compress
        Invoke-WebRequest -Method Put -Uri "$kc/admin/realms/epd/users/$uid/reset-password" -Headers @{ Authorization = "Bearer $adm" } -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($pw)) -UseBasicParsing | Out-Null
        $role = RoleRep $roleName
        $rbody = '[' + (@{ id = $role.id; name = $role.name } | ConvertTo-Json -Compress) + ']'
        Invoke-WebRequest -Method Post -Uri "$kc/admin/realms/epd/users/$uid/role-mappings/realm" -Headers @{ Authorization = "Bearer $adm" } -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($rbody)) -UseBasicParsing | Out-Null
        $script:ok++; Write-Output "  [OK] $username ($roleName, орг $org)"
    } catch {
        $script:fail++
        $code = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode.value__ } else { -1 }
        Write-Output "  [FAIL $code] $username : $($_.Exception.Message)"
    }
}
function MkInn($seed, $last) { ("{0:D8}" -f $seed).Substring(0, 8) + "$last" }

Write-Output "=== ДЕМО-ПОЛЬЗОВАТЕЛИ ПО КОМПАНИЯМ ==="
foreach ($c in 1..4) {
    $org = MkInn (10000000 + $c * 100) 0
    $docRma = MkInn (30000000 + $c * 100 + 1) 9   # врач (type 1)
    $mechRma = MkInn (30000000 + $c * 100 + 2) 9   # механик (type 2)
    $dispRma = MkInn (30000000 + $c * 100 + 3) 9   # диспетчер (type 3)
    $drvRma = MkInn (20000000 + $c * 100 + 1) 8    # водитель
    Write-Output "Компания $c (орг $org):"
    Upsert "disp$c" $org $dispRma 'DISPATCHER' "Диспетчер К$c"
    Upsert "doc$c"  $org $docRma  'DOCTOR'     "Врач К$c"
    Upsert "mech$c" $org $mechRma 'MECHANIC'   "Механик К$c"
    Upsert "drv$c"  $org $drvRma  'DRIVER'     "Водитель К$c"
}
Write-Output ''
Write-Output "=== ИТОГ: создано/обновлено=$ok, ошибок=$fail ==="
if ($fail -gt 0) { exit 1 } else { Write-Output 'ЛОГИНЫ: disp1..disp4 / doc1..doc4 / mech1..mech4 / drv1..drv4 (пароль = логин)'; exit 0 }
