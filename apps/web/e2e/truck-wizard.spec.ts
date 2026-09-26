import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { loginAs, PASSWORDS } from './fixtures';

/**
 * Мастер 2-Б при пустом справочнике клиентов (26.09): «Заказчик» обязателен (legacy Waybill2bRequest),
 * но клиенты архива не перенесены — заказчика можно записать по наименованию. Раньше «Далее» не
 * активировалось, и 2-Б через интерфейс выписать было нельзя.
 */
const fx = JSON.parse(readFileSync(resolve(process.cwd(), '../../scripts/e2e-fixture.json'), 'utf8'));

test('диспетчер: 2-Б через мастер с заказчиком по наименованию', async ({ page }) => {
  const truck = fx.forms.find((f: { type: string }) => f.type === 'WB_TRUCK');
  await loginAs(page, 'dispatcher');
  await page.goto('/waybills/new');

  await page.getByRole('button', { name: /Грузовой \(2-Б\)/ }).click();
  await page.getByRole('button', { name: 'Далее' }).click();

  await page.getByPlaceholder(/Введите госномер ТС/).fill(truck.plate);
  await page.getByText(truck.plate, { exact: true }).first().click();
  await page.getByPlaceholder(/Введите ИНН или Ф\.И\.О/).fill(truck.driverRma);
  await page.getByText(new RegExp(truck.driverRma)).first().click();
  await page.getByRole('button', { name: 'Далее' }).click();

  // Шаг 3: «Самт» из справочника направлений, «Заказчик» — по наименованию (справочник пуст).
  const samt = page.locator('div', { has: page.locator('label', { hasText: 'Самт' }) }).locator('select').last();
  await samt.selectOption({ index: 1 });
  const next = page.getByRole('button', { name: 'Далее' });
  await expect(next).toBeDisabled();
  await page.getByPlaceholder('Поиск заказчика по названию…').fill('Заказчик E2E');
  await page.getByText('Нет в справочнике — записать как введено').click();
  await expect(next).toBeEnabled({ timeout: 15_000 });
  await next.click();

  await page.getByRole('button', { name: 'Создать путевой лист' }).click();
  await page.waitForURL(/\/waybills\/[0-9a-f-]{36}$/, { timeout: 20_000 });
  const id = page.url().split('/').pop()!;

  const auth = await page.request.post('/md-api/api/v1/auth/token', {
    data: { username: 'dispatcher', password: PASSWORDS.dispatcher },
  });
  const headers = { Authorization: `Bearer ${(await auth.json()).access_token}` };
  try {
    const wb = await (await page.request.get(`/wb-api/api/v1/waybills/${id}`, { headers })).json();
    expect(wb.waybillType).toBe('WB_TRUCK');
    expect(wb.typeData.clientName).toBe('Заказчик E2E');
    expect(wb.typeData.clientId).toBeUndefined();
    expect(wb.typeData.directionId).toBeTruthy();
    // Заказчик виден на карточке (вкладка «Маршрут»; сверка 25.09, C5).
    await page.getByRole('button', { name: 'Маршрут', exact: true }).first().click();
    await expect(page.getByTestId('wb-client-name')).toHaveText('Заказчик E2E');
  } finally {
    await page.request.post(`/wb-api/api/v1/waybills/${id}/cancel`, { headers, data: { reason: 'e2e: уборка', actor: 'e2e' } });
  }
});

// Сверка 25.09, C5: «Мизоҷ» у 5Б-БМ — необязательный заказчик (legacy Waybill5bbm.client_id).
test('диспетчер: в мастере 5Б-БМ есть необязательный «Заказчик»', async ({ page }) => {
  const intl = fx.forms.find((f: { type: string }) => f.type === 'WB_TRUCK_INTL');
  await loginAs(page, 'dispatcher');
  await page.goto('/waybills/new');
  await page.getByRole('button', { name: /5Б-БМ/ }).first().click();
  await page.getByRole('button', { name: 'Далее' }).click();
  await page.getByPlaceholder(/Введите госномер ТС/).fill(intl.plate);
  await page.getByText(intl.plate, { exact: true }).first().click();
  await page.getByPlaceholder(/Введите ИНН или Ф\.И\.О/).fill(intl.driverRma);
  await page.getByText(new RegExp(intl.driverRma)).first().click();
  await page.getByRole('button', { name: 'Далее' }).click();
  const client = page.getByTestId('wb-client');
  await expect(client).toBeVisible({ timeout: 15_000 });
  await expect(client.locator('label')).not.toContainText('*');
});
