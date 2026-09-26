import { test, expect } from '@playwright/test';
import { loginAs, PASSWORDS } from './fixtures';

/**
 * Марка ТС — выбор из справочника марок (сверка 25.09, F4): расчёт ищет нормы расхода по имени марки,
 * опечатка свободного текста давала норму 0. Выбор «volv» → «Volvo» из справочника, запись под
 * справочным написанием; тестовое ТС удаляется.
 */
test('диспетчер: марка нового ТС выбирается из справочника', async ({ page }) => {
  const plate = '9998ZZ77';
  await loginAs(page, 'dispatcher');
  await page.goto('/fleet/vehicles');
  await page.getByRole('button', { name: 'Добавить ТС' }).click();
  await page.locator('#fleet-registrationNumber').fill(plate);
  await page.locator('#fleet-transportType').selectOption('2');
  const picker = page.locator('#fleet-brand');
  await picker.getByPlaceholder('Марка: название, модель или код…').fill('volv');
  await picker.getByText('Volvo', { exact: true }).first().click();
  await expect(picker).toContainText('Volvo');
  await page.getByRole('button', { name: 'Добавить', exact: true }).click();
  await expect(page.getByText('Добавлено', { exact: true })).toBeVisible();

  const auth = await page.request.post('/md-api/api/v1/auth/token', { data: { username: 'dispatcher', password: PASSWORDS.dispatcher } });
  const headers = { Authorization: `Bearer ${(await auth.json()).access_token}` };
  const list = await (await page.request.get(`/md-api/api/v1/vehicles?registrationNumber=${plate}`, { headers })).json();
  try {
    expect(list[0].brand).toBe('Volvo');
  } finally {
    if (list[0]) await page.request.delete(`/md-api/api/v1/vehicles/${list[0].id}`, { headers });
  }
});
