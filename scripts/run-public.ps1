# Запуск публичного портала проверки ПЛ по QR (apps/public) в dev-режиме на :3002.
#
# Публичный контур (интернет, аноним): единственная функция — проверка подлинности ПЛ
# по QR (/verify/{jws}). Ни авторизации, ни служебного кода — минимальная поверхность атаки.
# Обращается только к публичному эндпоинту waybill-сервиса (/wb-api/.../verify, без токена).
#
#   powershell -File scripts\run-public.ps1
#
# Чтобы QR из веб-консоли вёл сюда, запускайте консоль в профиле (run-web.ps1 -Profile ...):
# она проставляет NEXT_PUBLIC_VERIFY_BASE_URL=http://localhost:3002.
[CmdletBinding()]
param([int]$Port = 3002)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path "$PSScriptRoot\.."
$pubDir = Join-Path $root 'apps\public'

# Адрес waybill-сервиса для rewrite /wb-api (dev-дефолт). 127.0.0.1 — см. коммент в run-web.ps1.
if (-not $env:WB_API_URL) { $env:WB_API_URL = 'http://127.0.0.1:8082' }

Write-Host "Публичный портал проверки QR: порт $Port  (http://localhost:$Port)" -ForegroundColor Cyan
Push-Location $pubDir
try { npx next dev -p $Port }
finally { Pop-Location }
