import { expect, type Page } from '@playwright/test';

/**
 * Demo accounts of the platform sign-in (app_user, master-data). Passwords follow the password
 * policy and match scripts/demo-credentials.ps1 — change both together. Roles with a mandatory
 * second factor (SYSTEM_ADMIN, MINTRANS_ANALYST, INSPECTOR) are exercised through their
 * `*-automation` twins of the same role without 2FA; the 2FA flow itself is second-factor.spec.ts.
 */
export type Account = {
  username: string;
  password: string;
  role: string;
  /** Expected landing page after login (see apps/web/lib/roles.ts -> roleHome). */
  home: string;
};

export const PASSWORDS: Record<string, string> = {
  dispatcher: 'Epd-Qa-Tanzim-2026',
  doctor: 'Epd-Qa-Duxtur-2026',
  mechanic: 'Epd-Qa-Mexanik-2026',
  accountant: 'Epd-Qa-Buxgalter-2026',
  company: 'Epd-Qa-CompanyAdm-2026',
  branch: 'Epd-Qa-BranchAdm-2026',
  driver: 'Epd-Qa-Ronanda-2026',
  fuel: 'Epd-Qa-FuelStation-2026',
  'admin-automation': 'Epd-Qa-Automation-Admin-2026',
  'analyst-automation': 'Epd-Qa-Automation-Analyst-2026',
  'inspector-automation': 'Epd-Qa-Automation-Inspector-2026',
};

const acct = (username: string, role: string, home: string): Account =>
  ({ username, password: PASSWORDS[username], role, home });

export const ACCOUNTS: Account[] = [
  acct('dispatcher', 'DISPATCHER', '/dispatcher'),
  acct('doctor', 'DOCTOR', '/med'),
  acct('mechanic', 'MECHANIC', '/tech'),
  acct('accountant', 'ACCOUNTANT', '/reports/summary'),
  acct('admin-automation', 'SYSTEM_ADMIN', '/dashboard'),
  acct('company', 'COMPANY_ADMIN', '/dashboard'),
  acct('branch', 'BRANCH_ADMIN', '/dashboard'),
  acct('analyst-automation', 'MINTRANS_ANALYST', '/dashboard'),
  acct('driver', 'DRIVER', '/driver'),
  acct('inspector-automation', 'INSPECTOR', '/inspector'),
  acct('fuel', 'FUEL_STATION', '/fuel'),
];

/** Sign in with the demo password of `username` (see PASSWORDS). */
export async function loginAs(page: Page, username: string) {
  await login(page, username, PASSWORDS[username]);
}

/** Logs in via the app's own /login form (not the Keycloak screen) and waits for the
 *  post-login redirect into the user's cabinet to complete. */
export async function login(page: Page, username: string, password: string) {
  await page.goto('/login');
  await page.getByPlaceholder('Введите логин').fill(username);
  await page.getByPlaceholder('Введите пароль').fill(password);
  await page.getByRole('button', { name: 'Войти', exact: true }).click();
  // Успешный вход уводит с /login в кабинет роли; ждём именно ухода со страницы входа,
  // а не конкретного URL — homeFor() может отличаться, если конфиг ролей переопределён.
  await page.waitForURL(url => !url.pathname.startsWith('/login'), { timeout: 15_000 });
  // Дождаться каркаса приложения (сайдбар), а не просто смены URL.
  await expect(page.locator('.sidebar')).toBeVisible({ timeout: 15_000 });
}

export async function logout(page: Page) {
  await page.getByRole('button', { name: 'Выход', exact: true }).click();
  await page.waitForURL(url => url.pathname.startsWith('/login'), { timeout: 15_000 });
}

/** True if the page shows a hard crash / framework error overlay instead of app content. */
export async function hasCrashed(page: Page): Promise<boolean> {
  const bodyText = await page.locator('body').innerText().catch(() => '');
  return /Application error|Unhandled Runtime Error|This page could not be found|500/i.test(bodyText)
    && !(await page.locator('.sidebar').isVisible().catch(() => false));
}
