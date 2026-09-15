# =====================================================================
# Восстановление БД платформы ЭПД РТ из зашифрованной резервной копии (ИБ-13.8.4).
#
# По умолчанию восстанавливает в ИЗОЛИРОВАННУЮ тестовую БД (<db>_restore_test),
# НЕ в боевую — так требует регламент («восстановление в изолированной среде
# с проверкой целостности приложением»). Восстановление ПОВЕРХ боевой БД —
# только явно, параметром -TargetDb <реальное имя masterdata|waybill>,
# осознанное разрушительное действие (сносит целевую БД и создаёт заново).
#
# Запуск (тестовое восстановление):
#   powershell -File scripts\restore-postgres.ps1 -BackupFile backups\waybill-20260904-120000.dump.enc
# Запуск (поверх боевой БД — ТОЛЬКО осознанно):
#   powershell -File scripts\restore-postgres.ps1 -BackupFile backups\waybill-20260904-120000.dump.enc -TargetDb waybill
# =====================================================================
param(
    [Parameter(Mandatory = $true)][string]$BackupFile,
    [string]$TargetDb = ''
)
$ErrorActionPreference = 'Stop'

$pg = 'epd-prod-postgres'
$dbUser = 'epd'

function Read-EnvValue($key) {
    $envFile = Join-Path $PSScriptRoot '..\infra\.env'
    if (-not (Test-Path $envFile)) { throw "Не найден $envFile" }
    $line = Get-Content $envFile | Where-Object { $_ -match "^$key=" } | Select-Object -First 1
    if (-not $line) { throw "$key не задан в infra\.env" }
    return ($line -split '=', 2)[1]
}

if (-not (Test-Path $BackupFile)) { throw "Файл не найден: $BackupFile" }

$keyBytes = [Convert]::FromBase64String((Read-EnvValue 'BACKUP_ENCRYPTION_KEY'))
$hmacKeyBytes = [System.Security.Cryptography.SHA256]::Create().ComputeHash(
    $keyBytes + [Text.Encoding]::UTF8.GetBytes('epd-backup-hmac'))

$all = [IO.File]::ReadAllBytes($BackupFile)
if ($all.Length -lt 48) { throw "Файл повреждён (короче заголовка HMAC+IV)" }
$mac = $all[0..31]
$iv = $all[32..47]
$cipher = $all[48..($all.Length - 1)]

$hmac = New-Object System.Security.Cryptography.HMACSHA256(, $hmacKeyBytes)
$expectedMac = $hmac.ComputeHash($iv + $cipher)
$macOk = [System.Linq.Enumerable]::SequenceEqual([byte[]]$mac, [byte[]]$expectedMac)
if (-not $macOk) {
    throw "HMAC не совпадает — файл повреждён или подделан. Восстановление ОСТАНОВЛЕНО."
}
Write-Output "[OK] Целостность подтверждена (HMAC совпадает)"

$aes = [System.Security.Cryptography.Aes]::Create()
$aes.Key = $keyBytes
$aes.IV = $iv
$decryptor = $aes.CreateDecryptor()
$plainBytes = $decryptor.TransformFinalBlock($cipher, 0, $cipher.Length)

$baseName = [IO.Path]::GetFileNameWithoutExtension([IO.Path]::GetFileNameWithoutExtension($BackupFile))
$dbNameGuess = $baseName -replace '-\d{8}-\d{6}$', ''
if (-not $TargetDb) { $TargetDb = "${dbNameGuess}_restore_test" }

if ($TargetDb -in @('masterdata', 'waybill')) {
    Write-Output "[WARN] Восстановление ПОВЕРХ боевой БД '$TargetDb' — текущее содержимое будет уничтожено."
    Write-Output "[WARN] Прерывание — Ctrl+C в течение 10 секунд, если это не осознанное действие."
    Start-Sleep -Seconds 10
}

$localDump = Join-Path $env:TEMP "restore-$([Guid]::NewGuid()).dump"
$containerDump = "/tmp/epd-restore-$([Guid]::NewGuid()).dump"
[IO.File]::WriteAllBytes($localDump, $plainBytes)

try {
    docker cp $localDump "${pg}:${containerDump}" | Out-Null
    docker exec $pg psql -U $dbUser -d postgres -c "DROP DATABASE IF EXISTS $TargetDb;" | Out-Null
    docker exec $pg psql -U $dbUser -d postgres -c "CREATE DATABASE $TargetDb;" | Out-Null
    docker exec $pg pg_restore -U $dbUser -d $TargetDb --no-owner $containerDump
    Write-Output "[OK] Восстановлено в БД '$TargetDb' (источник: $BackupFile)"
} finally {
    Remove-Item $localDump -ErrorAction SilentlyContinue
    docker exec $pg rm -f $containerDump 2>$null | Out-Null
}
