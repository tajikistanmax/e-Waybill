import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { loginAs, PASSWORDS } from './fixtures';

/**
 * Черновик 1-АД (сверка 25.09, A18/A21): диспетчер исправляет шапку (график, особые отметки) до Т1,
 * затем подписывает Т1 с плановым временем выезда «Вақти баромад» — оно становится началом срока.
 * Лист — на автобус фикстуры, после теста аннулируется.
 */
const fx = JSON.parse(readFileSync(resolve(process.cwd(), '../../scripts/e2e-fixture.json'), 'utf8'));

test('диспетчер: правка шапки черновика и плановый выезд 1-АД при Т1', async ({ page }) => {
  const bus = fx.forms.find((f: { type: string }) => f.type === 'WB_BUS');
  const auth = await page.request.post('/md-api/api/v1/auth/token', { data: { username: 'dispatcher', password: PASSWORDS.dispatcher } });
  const headers = { Authorization: `Bearer ${(await auth.json()).access_token}` };
  const created = await page.request.post('/wb-api/api/v1/waybills', {
    headers,
    data: { waybillType: 'WB_BUS', organizationRma: fx.organization.rma, vehicleRegNumber: bus.plate, driverRma: bus.driverRma,
      communicationType: 'URBAN', route: bus.route, schedule: '06:00-22:00' },
  });
  expect(created.status()).toBe(201);
  const id = (await created.json()).id as string;
  try {
    await loginAs(page, 'dispatcher');
    await page.goto(`/waybills/${id}`);
    await page.getByTestId('draft-edit').click();
    const form = page.getByTestId('draft-edit-form');
    await form.getByLabel('График (реҷа)').fill('05:30-21:30');
    await form.getByLabel('Особая отметка').fill('E2E: правка черновика');
    await form.getByRole('button', { name: 'Сохранить' }).click();
    await expect(page.locator('main dl.kv').first()).toContainText('05:30-21:30');
    await expect(page.locator('main dl.kv').first()).toContainText('E2E: правка черновика');

    await page.getByLabel('Время выезда (план)').fill('05:45');
    await page.getByRole('button', { name: /Подписать Т1/ }).click();
    await expect(page.getByRole('button', { name: /Подписать Т1/ })).toHaveCount(0);
    const wbNow = await (await page.request.get(`/wb-api/api/v1/waybills/${id}`, { headers })).json();
    expect(wbNow.status).toBe('CREATED');
    const from = new Date(wbNow.validFrom);
    expect(`${String(from.getHours()).padStart(2, '0')}:${String(from.getMinutes()).padStart(2, '0')}`).toBe('05:45');
  } finally {
    await page.request.post(`/wb-api/api/v1/waybills/${id}/cancel`, { headers, data: { reason: 'e2e: уборка', actor: 'e2e' } });
  }
});
