import { test, expect, type APIRequestContext } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { loginAs, PASSWORDS } from './fixtures';

/**
 * Борхатҳо листа 2-Б (V30, legacy cargo_waybills): диспетчер добавляет несколько накладных замимаи 1/2
 * во вкладке «Накладная», сервер считает P и Z листа по строкам (CargoFuelBase::calcP/calcZ).
 * Лист — черновик на ТС фикстуры (черновик не занимает ТС), после теста аннулируется.
 */
const fx = JSON.parse(readFileSync(resolve(process.cwd(), '../../scripts/e2e-fixture.json'), 'utf8'));

async function token(request: APIRequestContext, username: string): Promise<string> {
  const r = await request.post('/md-api/api/v1/auth/token', { data: { username, password: PASSWORDS[username] } });
  expect(r.ok()).toBeTruthy();
  return (await r.json()).access_token as string;
}

test('диспетчер: несколько борхатов 2-Б, итоги P и Z по строкам', async ({ page, request }) => {
  const truck = fx.forms.find((f: { type: string }) => f.type === 'WB_TRUCK');
  const auth = { Authorization: `Bearer ${await token(request, 'dispatcher')}` };
  const created = await request.post('/wb-api/api/v1/waybills', {
    headers: auth,
    data: {
      waybillType: 'WB_TRUCK', organizationRma: fx.organization.rma, vehicleRegNumber: truck.plate,
      driverRma: truck.driverRma, communicationType: 'INTERCITY',
      typeData: { shipmentKind: 'PIECEWORK', directionId: 2, workRegions: [1] },
    },
  });
  expect(created.status()).toBe(201);
  const id = (await created.json()).id as string;

  try {
    await loginAs(page, 'dispatcher');
    await page.goto(`/waybills/${id}`);
    await page.getByRole('button', { name: 'Накладная', exact: true }).click();
    const form = page.getByTestId('cnn-form');
    await expect(form).toBeVisible();

    const pick = async (placeholder: RegExp, text: string) => {
      await form.getByPlaceholder(placeholder).fill(text);
      await page.getByText(text, { exact: true }).last().click();
    };
    const today = new Date().toISOString().slice(0, 10);

    // Замимаи 1: 10 т × 40 км × 3 рейса = 1200 т·км, Z = 3.
    await form.getByLabel('Дата').fill(today);
    await pick(/заказчик/i, 'Мизоҷ E2E');
    await pick(/отправитель/i, 'Фиристанда E2E');
    await pick(/получатель/i, 'Гиранда E2E');
    await pick(/груз/i, 'Бор E2E');
    await form.getByLabel('Количество груза').fill('100');
    await form.getByLabel('Масса, т').fill('10');
    await form.getByLabel('Расстояние, км').fill('40');
    await form.getByLabel('Рейсов').fill('3');
    await form.getByRole('button', { name: '+ Добавить борхат' }).click();
    await expect(page.getByTestId('cnn-totals')).toContainText(/P = 1\s?200 т·км, Z = 3/);
    // Форма следующего борхата готова: стороны и груз подставлены, количество и масса — пустые.
    await expect(form.getByLabel('Количество груза')).toHaveValue('');
    await expect(form).toContainText('Мизоҷ E2E');

    // Замимаи 2 (стороны и груз подставлены из предыдущего): 5 т × 40 км = 200 т·км, ездка = борхат.
    await form.getByLabel('Вид борхата').selectOption('2');
    await form.getByLabel('Количество груза').fill('50');
    await form.getByLabel('Масса, т').fill('5');
    await form.getByRole('button', { name: '+ Добавить борхат' }).click();
    await expect(page.getByTestId('cnn-totals')).toContainText(/P = 1\s?400 т·км, Z = 4/);

    const rows = page.getByTestId('wb-consignment-notes').locator('tbody tr');
    await expect(rows).toHaveCount(3);   // 2 борхата + строка итогов
    await expect(rows.nth(1)).toContainText('Замимаи 2');
    await expect(rows.nth(1).locator('td').nth(9)).toHaveText('—');   // рейсы у замимаи 2 не ведутся
    await expect(page.locator('.error')).toHaveCount(0);
  } finally {
    await request.post(`/wb-api/api/v1/waybills/${id}/cancel`, { headers: auth, data: { reason: 'e2e consignment notes' } });
  }
});
