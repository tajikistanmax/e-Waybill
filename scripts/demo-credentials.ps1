# Единые пароли демо/тестовых учётных записей реалма epd (Keycloak).
# После включения парольной политики реалма (length(12) + notUsername + notEmail +
# passwordHistory(3), см. infra/keycloak/epd-realm.json) пароль = логину больше
# не проходит импорт реалма — эти пароли синхронизированы 1:1 с credentials
# в epd-realm.json. Меняются в ОБОИХ местах одновременно.
$DemoPasswords = @{
    dispatcher = 'Epd-Qa-Tanzim-2026'
    doctor     = 'Epd-Qa-Duxtur-2026'
    mechanic   = 'Epd-Qa-Mexanik-2026'
    accountant = 'Epd-Qa-Buxgalter-2026'
    admin      = 'Epd-Qa-AdminRoot-2026'
    company    = 'Epd-Qa-CompanyAdm-2026'
    branch     = 'Epd-Qa-BranchAdm-2026'
    analyst    = 'Epd-Qa-Analyst-2026'
    driver     = 'Epd-Qa-Ronanda-2026'
    inspector  = 'Epd-Qa-Nozir-2026'
    fuel       = 'Epd-Qa-FuelStation-2026'
    # admin/analyst/inspector требуют CONFIGURE_TOTP (обязательная 2FA, ИБ-13.2.2) —
    # прямой grant_type=password для них больше не проходит. QA-скрипты используют
    # эти *-automation учётки той же роли, но без 2FA.
    'admin-automation'     = 'Epd-Qa-Automation-Admin-2026'
    'analyst-automation'   = 'Epd-Qa-Automation-Analyst-2026'
    'inspector-automation' = 'Epd-Qa-Automation-Inspector-2026'
}

function Get-DemoPassword($username) {
    if ($DemoPasswords.ContainsKey($username)) { return $DemoPasswords[$username] }
    return $username
}
