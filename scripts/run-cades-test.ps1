# =====================================================================
# Включить РЕЖИМ КВАЛИФИЦИРОВАННОЙ ЭЦП (cades) с ТЕСТОВЫМ крипто-сервисом УЦ.
# Собирает и запускает apps\backend\crypto-service-mock (имитация Crypto Service УЦ РТ),
# затем перезапускает waybill-service в режиме epd.signing.mode=cades, направив подпись
# титулов Т1–Т6 на тест-УЦ. Так весь процесс подписания проверяется «как в бою».
# Возврат к заглушке (dev): перезапустить waybill-service без этих переменных окружения.
# Запуск: powershell -File scripts\run-cades-test.ps1
# Требует: JDK 21, собранный waybill-service (build\libs\*.jar), Keycloak :8180.
# =====================================================================
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
$javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$mockDir = 'D:\Projects\e-Waybill\apps\backend\crypto-service-mock'
$sp = 'C:\Users\Lenovo\AppData\Local\Temp\claude\C--Users-Lenovo\d45c38ac-da80-4d5c-9d90-2ed83468d0c4\scratchpad'
$mockLog = Join-Path $sp 'crypto-mock.log'

Write-Output '=== 1) Сборка и запуск тестового крипто-сервиса УЦ (порт 9099) ==='
& $javac (Join-Path $mockDir 'CryptoServiceMock.java')
$p9099 = Get-NetTCPConnection -LocalPort 9099 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($p9099) { Stop-Process -Id $p9099.OwningProcess -Force; Start-Sleep -Seconds 1 }
Start-Process $java -ArgumentList @('-cp', $mockDir, 'CryptoServiceMock', '9099') -WindowStyle Hidden -RedirectStandardOutput $mockLog
Start-Sleep -Seconds 3
$qrKey = ((Get-Content $mockLog | Where-Object { $_ -like 'QR_SIGNING_KEY=*' } | Select-Object -First 1) -replace '^QR_SIGNING_KEY=', '')
if (-not $qrKey) { Write-Output 'НЕ удалось получить QR-ключ из крипто-сервиса'; exit 1 }
try { $h = (Invoke-RestMethod 'http://localhost:9099/' -TimeoutSec 3).status } catch { $h = 'DOWN' }
Write-Output "  крипто-сервис: $h; ключ QR получен"

Write-Output '=== 2) Перезапуск waybill-service в режиме cades ==='
$env:SIGNING_MODE = 'cades'
$env:SIGNING_CRYPTO_URL = 'http://localhost:9099'
$env:QR_SIGNING_KEY = $qrKey
$p = Get-NetTCPConnection -LocalPort 8082 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($p) { Stop-Process -Id $p.OwningProcess -Force; Start-Sleep -Seconds 2 }
$jar = (Get-ChildItem 'D:\Projects\e-Waybill\apps\backend\waybill-service\build\libs\*.jar' | Where-Object { $_.Name -notlike '*-plain.jar' } | Select-Object -First 1).FullName
Start-Process $java -ArgumentList @('-jar', $jar) -WindowStyle Hidden
$ok = $false
foreach ($i in 1..40) { Start-Sleep -Seconds 2; try { if ((Invoke-RestMethod 'http://localhost:8082/actuator/health' -TimeoutSec 3).status -eq 'UP') { $ok = $true; break } } catch {} }
Write-Output "  waybill-service (cades) UP=$ok"
Write-Output ''
if ($ok) {
    Write-Output 'ГОТОВО: титулы путевых листов теперь подписываются через тестовый крипто-сервис УЦ.'
    Write-Output 'Возврат к заглушке: перезапустить waybill-service без SIGNING_MODE/QR_SIGNING_KEY.'
} else { Write-Output 'waybill-service не поднялся — см. логи'; exit 1 }
