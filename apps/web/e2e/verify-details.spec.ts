import { test, expect, request } from '@playwright/test';

/**
 * Сверка 25.09, G6 — публичная страница проверки QR (портал apps/public) показывает то же, что старая
 * страница «Роҳхат» для сверки на месте: водительское удостоверение (номер — только хвост), карту
 * контроля, маршрут; паспорт водителя не показывается.
 */

const MD = process.env.E2E_MD_URL || 'http://localhost:8081';
const WB = process.env.E2E_WB_URL || 'http://localhost:8082';
const PUBLIC = process.env.E2E_PUBLIC_BASE_URL || 'http://localhost:3002';
const ADMIN = process.env.E2E_ADMIN_USER || 'admin-automation';
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD || 'Epd-Qa-Automation-Admin-2026';

test('страница проверки QR: ВУ, карта контроля, маршрут; без паспорта', async ({ page }) => {
  const ctx = await request.newContext();
  const tok = await ctx.post(`${MD}/api/v1/auth/token`, { data: { username: ADMIN, password: ADMIN_PASSWORD } });
  expect(tok.status()).toBe(200);
  const h = { Authorization: `Bearer ${(await tok.json()).access_token}` };

  const list = await (await ctx.get(`${WB}/api/v1/waybills?status=ACTIVE`, { headers: h })).json();
  const wb = (list as Array<{ id: string; number?: string; route?: string }>).find(w => w.number && w.route);
  test.skip(!wb, 'нет действующего листа с маршрутом на стенде');
  const { jws } = await (await ctx.get(`${WB}/api/v1/waybills/${wb!.id}/qr`, { headers: h })).json();
  await ctx.dispose();

  await page.goto(`${PUBLIC}/verify/${jws}`);
  await expect(page.getByText('Водительское удостоверение')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('verify-license')).toContainText('•••• ');
  await expect(page.getByText('Маршрут', { exact: true })).toBeVisible();
  await expect(page.getByText(wb!.route!)).toBeVisible();
  await expect(page.getByText(/паспорт/i)).toHaveCount(0);
});
