# Roles, cabinets, access, companies and branches - repeatable live run (Playwright).
#
# What it checks (apps/web/e2e/roles-cabinets.spec.ts):
#  1) "new carrier": platform admin grants a company admin (login = phone, temp password shown once)
#     -> first login -> forced password change on /auth/password (UI) -> company admin sees company
#     and branch -> grants branch admin, dispatcher, doctor, mechanic, accountant, fuel station and
#     driver (bound to a driver record) -> each logs in, changes password, lands in own cabinet with
#     own data scope -> disable / enable / reset password / change role / delete -> lock after 10
#     failed logins -> logout revokes refresh token -> scheduled token refresh in the browser.
#     Test company 990000500 + branch 990000501 are created and removed by the test itself.
#  2) 14 demo logins: every menu item and 88 sub-pages - screenshot, no API 4xx/5xx, no console
#     errors, no untranslated i18n keys, no redirects; company fleet CRUD into a branch; the role
#     matrix on /settings/roles really changes the menu (restored afterwards).
#
# Usage (stack already running: web :3000, master-data :8081):
#   powershell -File scripts\e2e-roles-cabinets.ps1                 # everything
#   powershell -File scripts\e2e-roles-cabinets.ps1 -Only carrier   # only the new-carrier scenario
#   powershell -File scripts\e2e-roles-cabinets.ps1 -Only cabinets  # only the demo-login walk
# Screenshots and JSON report: -Shots <dir> (default: apps\web\e2e-shots).
param(
    [ValidateSet('all', 'carrier', 'cabinets')][string]$Only = 'all',
    [string]$Shots = '',
    [string]$BaseUrl = 'http://localhost:3000',
    [string]$MdUrl = 'http://localhost:8081'
)
$ErrorActionPreference = 'Stop'
$web = Join-Path $PSScriptRoot '..\apps\web'
if (-not $Shots) { $Shots = Join-Path $web 'e2e-shots' }
$env:E2E_BASE_URL = $BaseUrl
$env:E2E_MD_URL = $MdUrl
$env:E2E_SHOTS = $Shots
$grep = @()
# Test titles are Russian; the filter is built from char codes to keep this file ASCII-only.
# No '|' in the pattern: npx is a .cmd shim and cmd.exe would treat it as a pipe.
if ($Only -eq 'carrier') { $grep = @('-g', (-join @([char]0x043D, [char]0x043E, [char]0x0432, [char]0x044B, [char]0x0439))) }
if ($Only -eq 'cabinets') { $grep = @('-g', (-join @([char]0x043A, [char]0x0430, [char]0x0431, [char]0x0438, [char]0x043D, [char]0x0435, [char]0x0442, [char]0x044B))) }
Push-Location $web
# Node warnings go to stderr (e.g. "NO_COLOR is ignored due to FORCE_COLOR"); with 'Stop' and a
# redirected console PowerShell 5.1 turns them into a terminating NativeCommandError. The result
# is judged by the exit code only.
$ErrorActionPreference = 'Continue'
try {
    # Playwright CLI directly through node: the npx .cmd/.ps1 shims mangle non-ASCII arguments.
    & node node_modules/@playwright/test/cli.js test e2e/roles-cabinets.spec.ts --retries=0 --reporter=line @grep
    $code = $LASTEXITCODE
} finally {
    Pop-Location
    $ErrorActionPreference = 'Stop'
}
$report = Join-Path $Shots 'roles-cabinets-report.json'
if (Test-Path $report) {
    $issues = Get-Content $report -Raw -Encoding UTF8 | ConvertFrom-Json
    Write-Output ("Issues recorded: {0} (details: {1})" -f @($issues).Count, $report)
}
Write-Output "Screenshots: $Shots"
exit $code
