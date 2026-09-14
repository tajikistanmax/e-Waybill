# =====================================================================
# Резервное копирование БД платформы ЭПД РТ (ИБ-13.8): pg_dump обеих баз
# (masterdata, waybill) из контейнера epd-prod-postgres + шифрование
# AES-256-CBC + HMAC-SHA256 (encrypt-then-MAC) + ротация по сроку хранения.
#
# Запуск: powershell -File scripts\backup-postgres.ps1
# Требует: запущенный контейнер epd-prod-postgres, BACKUP_ENCRYPTION_KEY
#          в infra\.env (256-битный ключ, Base64).
#
# Восстановление: scripts\restore-postgres.ps1 -BackupFile backups\<файл>.dump.enc
#
# ВАЖНО (честно о пределах этого скрипта):
#   - Это ежедневный снапшот (pg_dump), НЕ непрерывная архивация WAL — RPO
#     ограничен интервалом между запусками, а не близок к нулю, как того
#     требует ИБ-13.8.1 (pgBackRest + WAL) для боевого контура.
#   - Схема хранения 3-2-1 (офсайт-копия, отдельный носитель/резервная площадка)
#     не реализована — копии остаются на этой же машине, в backups/.
#   - Планировщик (расписание запуска) не настраивается автоматически — это
#     обдуманное решение (регистрация задачи в Task Scheduler — персистентное
#     системное изменение, не делается втихую); см. README ниже по запуску вручную
#     по расписанию в реальном ЦОД-развёртывании.
# =====================================================================
$ErrorActionPreference = 'Stop'

$pg = 'epd-prod-postgres'
$dbUser = 'epd'
$databases = @('masterdata', 'waybill')
$backupDir = Join-Path $PSScriptRoot '..\backups'
$retentionDays = 30

function Read-EnvValue($key) {
    $envFile = Join-Path $PSScriptRoot '..\infra\.env'
    if (-not (Test-Path $envFile)) { throw "Не найден $envFile" }
    $line = Get-Content $envFile | Where-Object { $_ -match "^$key=" } | Select-Object -First 1
    if (-not $line) { throw "$key не задан в infra\.env" }
    return ($line -split '=', 2)[1]
}

$keyBytes = [Convert]::FromBase64String((Read-EnvValue 'BACKUP_ENCRYPTION_KEY'))
if ($keyBytes.Length -ne 32) { throw "BACKUP_ENCRYPTION_KEY должен быть 256-битным (32 байта после Base64)" }
# Отдельный HMAC-ключ, производный от основного (простое разделение назначения
# ключей для encrypt-then-MAC без второго секрета в .env).
$hmacKeyBytes = [System.Security.Cryptography.SHA256]::Create().ComputeHash(
    $keyBytes + [Text.Encoding]::UTF8.GetBytes('epd-backup-hmac'))

function Protect-File([string]$inputPath, [string]$outputPath) {
    $aes = [System.Security.Cryptography.Aes]::Create()
    $aes.Key = $keyBytes
    $aes.GenerateIV()
    $iv = $aes.IV
    $encryptor = $aes.CreateEncryptor()
    $inBytes = [IO.File]::ReadAllBytes($inputPath)
    $cipherBytes = $encryptor.TransformFinalBlock($inBytes, 0, $inBytes.Length)
    $hmac = New-Object System.Security.Cryptography.HMACSHA256(, $hmacKeyBytes)
    $mac = $hmac.ComputeHash($iv + $cipherBytes)
    # Формат файла: [32 байта HMAC][16 байт IV][ciphertext]
    $out = [IO.File]::Open($outputPath, [IO.FileMode]::Create)
    try {
        $out.Write($mac, 0, $mac.Length)
        $out.Write($iv, 0, $iv.Length)
        $out.Write($cipherBytes, 0, $cipherBytes.Length)
    } finally { $out.Close() }
}

if (-not (Test-Path $backupDir)) { New-Item -ItemType Directory -Path $backupDir | Out-Null }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$ok = 0; $fail = 0

Write-Output "=== РЕЗЕРВНОЕ КОПИРОВАНИЕ БД ЭПД РТ ($stamp) ==="

foreach ($db in $databases) {
    Write-Output "--- $db ---"
    $containerDump = "/tmp/epd-backup-$db-$stamp.dump"
    $localDump = Join-Path $env:TEMP "epd-$db-$stamp.dump"
    try {
        docker exec $pg pg_dump -U $dbUser -Fc -d $db -f $containerDump
        docker cp "${pg}:${containerDump}" $localDump | Out-Null
        docker exec $pg rm -f $containerDump | Out-Null
        if (-not (Test-Path $localDump) -or (Get-Item $localDump).Length -eq 0) {
            throw "pg_dump вернул пустой или отсутствующий файл"
        }
        $encPath = Join-Path $backupDir "$db-$stamp.dump.enc"
        Protect-File -inputPath $localDump -outputPath $encPath
        $size = (Get-Item $encPath).Length
        Write-Output "  [OK] $encPath ($size байт, зашифровано)"
        $ok++
    } catch {
        Write-Output "  [FAIL] ${db}: $($_.Exception.Message)"
        $fail++
    } finally {
        Remove-Item $localDump -ErrorAction SilentlyContinue
    }
}

# --- Ротация: удалить копии старше retentionDays (оперативное хранение — 30 суток, ИБ-13.8.5) ---
$cutoff = (Get-Date).AddDays(-$retentionDays)
Get-ChildItem $backupDir -Filter '*.dump.enc' -ErrorAction SilentlyContinue |
    Where-Object { $_.LastWriteTime -lt $cutoff } |
    ForEach-Object {
        Write-Output "  [ROTATE] удаляю устаревшую копию: $($_.Name)"
        Remove-Item $_.FullName -Force
    }

Write-Output ""
Write-Output "=== ИТОГ: OK=$ok, FAIL=$fail ==="
if ($fail -gt 0) { exit 1 }
