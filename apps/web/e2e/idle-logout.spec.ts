import { test, expect, type Page } from '@playwright/test';

/**
 * ИБ-13.2.5: привилегированные роли (администратор платформы, инспектор, аналитик) выходят из
 * системы после 30 минут бездействия, даже если настройка «Авто-выход при бездействии» выключена;
 * остальные роли — по настройке (на стенде 0 — не выходят). Время ускоряется часами браузера.
 */

async function login(page: Page, username: string, password: string) {
  await page.goto('/login');
  await page.getByPlaceholder('Введите логин').fill(username);
  await page.getByPlaceholder('Введите пароль').fill(password);
  await page.getByRole('button', { name: 'Войти', exact: true }).click();
  await expect(page.locator('.sidebar')).toBeVisible({ timeout: 20_000 });
}

test('администратор платформы: выход после 30 минут бездействия', async ({ page }) => {
  await page.clock.install();
  await login(page, 'admin-automation', 'Epd-Qa-Automation-Admin-2026');
  await page.waitForTimeout(1500); // настройки безопасности загрузились, таймер взведён
  await page.clock.fastForward('29:00');
  await expect(page.locator('.sidebar')).toBeVisible();
  await page.clock.fastForward('02:00');
  await page.waitForURL(u => u.pathname.startsWith('/login'), { timeout: 15_000 });
});

test('диспетчер: при выключенной настройке через 31 минуту остаётся в системе', async ({ page }) => {
  await page.clock.install();
  await login(page, 'dispatcher', 'Epd-Qa-Tanzim-2026');
  await page.waitForTimeout(1500);
  await page.clock.fastForward('31:00');
  await page.waitForTimeout(1500);
  expect(new URL(page.url()).pathname.startsWith('/login')).toBe(false);
  await expect(page.locator('.sidebar')).toBeVisible();
});
