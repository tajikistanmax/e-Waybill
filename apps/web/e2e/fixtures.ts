import { expect, type Page } from '@playwright/test';

/**
 * Demo accounts (Keycloak realm "epd", infra/keycloak/epd-realm.json).
 * Password equals username for every one of these, per project convention.
 */
export type Account = {
  username: string;
  password: string;
  role: string;
  /** Expected landing page after login (see apps/web/lib/roles.ts -> roleHome). */
  home: string;
};

export const ACCOUNTS: Account[] = [
  { username: 'dispatcher', password: 'dispatcher', role: 'DISPATCHER', home: '/dispatcher' },
  { username: 'doctor', password: 'doctor', role: 'DOCTOR', home: '/med' },
  { username: 'mechanic', password: 'mechanic', role: 'MECHANIC', home: '/tech' },
  { username: 'accountant', password: 'accountant', role: 'ACCOUNTANT', home: '/reports/summary' },
  { username: 'admin', password: 'admin', role: 'SYSTEM_ADMIN', home: '/dashboard' },
  { username: 'company', password: 'company', role: 'COMPANY_ADMIN', home: '/dashboard' },
  { username: 'branch', password: 'branch', role: 'BRANCH_ADMIN', home: '/dashboard' },
  { username: 'analyst', password: 'analyst', role: 'MINTRANS_ANALYST', home: '/dashboard' },
  { username: 'driver', password: 'driver', role: 'DRIVER', home: '/driver' },
  { username: 'inspector', password: 'inspector', role: 'INSPECTOR', home: '/inspector' },
  { username: 'fuel', password: 'fuel', role: 'FUEL_STATION', home: '/fuel' },
];

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
