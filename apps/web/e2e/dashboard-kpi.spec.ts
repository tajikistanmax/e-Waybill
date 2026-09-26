import { test, expect } from '@playwright/test';
import { loginAs, PASSWORDS } from './fixtures';

/**
 * Главная панель (сверка 25.09, H4): показатели считает сервер по всей области видимости
 * (GET /api/v1/dashboard), а не браузер по 1000 последним листам. Плитки совпадают с ответом API.
 */
for (const who of ['admin-automation', 'dispatcher']) {
  test(`${who}: плитки панели = агрегаты сервера`, async ({ page }) => {
    await loginAs(page, who);
    const auth = await page.request.post('/md-api/api/v1/auth/token', { data: { username: who, password: PASSWORDS[who] } });
    const headers = { Authorization: `Bearer ${(await auth.json()).access_token}` };
    const stats = await (await page.request.get('/wb-api/api/v1/dashboard', { headers })).json();
    expect(stats.days).toHaveLength(7);

    await page.goto('/dashboard');
    const tile = (label: string) => page.locator('.k-label', { hasText: label }).locator('xpath=following-sibling::div[1]');
    const ru = (n: number) => new RegExp('^' + n.toLocaleString('ru-RU').replace(/\s/g, '\\s') + '$');
    await expect(tile('Путевых листов')).toHaveText(ru(stats.total));
    await expect(tile('Завершено')).toHaveText(ru(stats.completed));
    await expect(page.locator('.error')).toHaveCount(0);
  });
}
