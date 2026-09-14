# Запуск веб-консоли в dev-режиме в нужном профиле.
#
#   all       — единый монолит (все роли), поведение до разделения (distDir .next). По умолчанию.
#   waybill   — кабинет перевозчика (диспетчер/водитель/механик/врач/бухгалтер/АЗС/админ компании)
#   oversight — платформа надзора (сис-админ, аналитик Минтранса, инспектор)
#
# Профили waybill и oversight можно запускать ОДНОВРЕМЕННО (разные порты и distDir):
#   powershell -File scripts\run-web.ps1 -Profile waybill      # :3000
#   powershell -File scripts\run-web.ps1 -Profile oversight    # :3001
# Публичный портал проверки QR — отдельным приложением: scripts\run-public.ps1 (:3002).
#
# Перед запуском поднять backend: infra (docker compose -f infra/docker-compose.yml up -d)
# + master-data :8081 и waybill :8082 (см. README «Запуск dev-окружения»).
[CmdletBinding()]
param(
    [ValidateSet('all', 'waybill', 'oversight')][string]$Profile = 'all',
    [int]$Port = 0
)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path "$PSScriptRoot\.."
$webDir = Join-Path $root 'apps\web'

# Порт по умолчанию для профиля (waybill и oversight не конфликтуют — можно рядом).
if ($Port -eq 0) { $Port = @{ all = 3000; waybill = 3000; oversight = 3001 }[$Profile] }

if ($Profile -eq 'all') {
    # Монолит: БЕЗ переменной профиля (distDir=.next, rolesInProfile всегда true) —
    # ссылки verify/сосед не задаём, /verify обслуживает сам монолит (fallback на origin).
    Remove-Item Env:NEXT_PUBLIC_APP_PROFILE   -ErrorAction SilentlyContinue
    Remove-Item Env:NEXT_PUBLIC_WAYBILL_URL   -ErrorAction SilentlyContinue
    Remove-Item Env:NEXT_PUBLIC_OVERSIGHT_URL -ErrorAction SilentlyContinue
    Remove-Item Env:NEXT_PUBLIC_VERIFY_BASE_URL -ErrorAction SilentlyContinue
}
else {
    $env:NEXT_PUBLIC_APP_PROFILE = $Profile
    # Ссылки на «соседний» кабинет (экран «Это не ваш кабинет») и на публичный портал (QR).
    $env:NEXT_PUBLIC_WAYBILL_URL     = 'http://localhost:3000'
    $env:NEXT_PUBLIC_OVERSIGHT_URL   = 'http://localhost:3001'
    $env:NEXT_PUBLIC_VERIFY_BASE_URL = 'http://localhost:3002'
}

# Адреса backend для rewrites Next (dev-дефолты). 127.0.0.1, не localhost: на Windows
# undici иначе резолвит в IPv6 ::1 и прокси-запрос к backend зависает.
if (-not $env:MD_API_URL) { $env:MD_API_URL = 'http://127.0.0.1:8081' }
if (-not $env:WB_API_URL) { $env:WB_API_URL = 'http://127.0.0.1:8082' }

Write-Host "Веб-консоль: профиль=$Profile, порт=$Port  (http://localhost:$Port)" -ForegroundColor Cyan
Push-Location $webDir
try { npx next dev -p $Port }
finally { Pop-Location }
