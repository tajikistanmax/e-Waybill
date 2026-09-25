import { test, expect, request, type APIRequestContext } from '@playwright/test';
import { loginAs, PASSWORDS } from './fixtures';

/**
 * Конструктор дополнительных полей ПЛ («Настройки → Конструктор полей») — вся цепочка на живом
 * стенде: администратор заводит, меняет и скрывает поля → поле появляется в мастере ПЛ (скрытое —
 * нет) → сервер проверяет обязательность и вариант списка → значение видно в карточке листа.
 * Вид ПЛ — спецтехника демо-компании; тестовые поля и лист убираются за собой.
 */

const MD = process.env.E2E_MD_URL || 'http://localhost:8081';
const WB = process.env.E2E_WB_URL || 'http://localhost:8082';
const TYPE = 'WB_SPECIAL';
const KIND = 'e2eKind';
const NOTE = 'e2eNote';

async function bearer(ctx: APIRequestContext, username: string) {
  const r = await ctx.post(`${MD}/api/v1/auth/token`, { data: { username, password: PASSWORDS[username] } });
  expect(r.status(), `вход ${username}`).toBe(200);
  return { Authorization: `Bearer ${String((await r.json()).access_token)}` };
}

test.describe('Конструктор полей', () => {
  let ctx: APIRequestContext;
  let admin: Record<string, string>;
  let disp: Record<string, string>;
  const created: string[] = [];

  async function cleanupFields() {
    const r = await ctx.get(`${MD}/api/v1/field-definitions?waybillType=${TYPE}&all=true`, { headers: admin });
    for (const d of await r.json()) {
      if ([KIND, NOTE].includes(d.fieldKey)) await ctx.delete(`${MD}/api/v1/field-definitions/${d.id}`, { headers: admin });
    }
  }

  test.beforeAll(async () => {
    ctx = await request.newContext();
    admin = await bearer(ctx, 'admin-automation');
    disp = await bearer(ctx, 'dispatcher');
    await cleanupFields();
  });

  test.afterAll(async () => {
    for (const id of created) {
      await ctx.post(`${WB}/api/v1/waybills/${id}/cancel`, { headers: disp, data: { reason: 'e2e: уборка', actor: 'e2e' } });
    }
    await cleanupFields();
    await ctx.dispose();
  });

  test('администратор заводит, меняет и скрывает поля; мастер и сервер их учитывают', async ({ page }) => {
    page.on('dialog', d => d.accept());
    await loginAs(page, 'admin-automation');
    await page.goto('/settings/fields');
    await page.locator('select').first().selectOption(TYPE);

    // Неверный ключ — понятная ошибка, поле не создаётся.
    await page.getByPlaceholder('cargoWeight').fill('моё поле');
    await page.getByLabel('Название (рус.)', { exact: true }).fill('Плохое');
    await page.getByRole('button', { name: 'Добавить поле' }).click();
    await expect(page.locator('.error')).toContainText('латинские буквы');

    // Список (обязательный, с таджикским названием) и текст.
    await page.getByPlaceholder('cargoWeight').fill(KIND);
    await page.getByLabel('Название (рус.)', { exact: true }).fill('Класс груза (e2e)');
    await page.getByLabel('Название (тадж.)', { exact: true }).fill('Синфи бор (e2e)');
    await page.getByLabel('Тип данных', { exact: true }).selectOption('ENUM');
    await page.getByLabel('Варианты (через запятую)', { exact: true }).fill('Лёгкий, Тяжёлый');
    await page.getByLabel('Обязательное', { exact: true }).check();
    await page.getByRole('button', { name: 'Добавить поле' }).click();
    const kindRow = page.locator('tbody tr', { hasText: KIND });
    await expect(kindRow).toContainText('Синфи бор (e2e)');
    await expect(kindRow).toContainText('Лёгкий, Тяжёлый');

    await page.getByPlaceholder('cargoWeight').fill(NOTE);
    await page.getByLabel('Название (рус.)', { exact: true }).fill('Примечание');
    await page.getByRole('button', { name: 'Добавить поле' }).click();
    const noteRow = page.locator('tbody tr', { hasText: NOTE });
    await expect(noteRow).toBeVisible();

    // Изменить название, затем скрыть.
    await noteRow.getByRole('button', { name: 'Изменить' }).click();
    await page.locator('tbody tr', { hasText: NOTE }).getByLabel('Название (рус.)').fill('Примечание (e2e)');
    await page.locator('tbody tr', { hasText: NOTE }).getByRole('button', { name: 'Сохранить' }).click();
    await expect(noteRow).toContainText('Примечание (e2e)');
    await noteRow.getByRole('button', { name: 'Скрыть' }).click();
    await expect(noteRow).toContainText('скрыто');

    // Мастер ПЛ (диспетчер): видны только показываемые поля этого вида.
    const defs = await (await ctx.get(`${MD}/api/v1/field-definitions?waybillType=${TYPE}`, { headers: disp })).json();
    const keys = defs.map((d: { fieldKey: string }) => d.fieldKey);
    expect(keys).toContain(KIND);
    expect(keys).not.toContain(NOTE);

    // Сервер: обязательность и вариант списка.
    const body = (custom?: Record<string, string>) => ({
      waybillType: TYPE, organizationRma: '025680800', vehicleRegNumber: '9919QA01', driverRma: '990000009',
      communicationType: 'URBAN', schedule: '06:00-22:00',
      typeData: { workType: 'Планировка', motorHoursExit: 1300.5, workObject: 'e2e', ...(custom ? { custom } : {}) },
    });
    const noKind = await ctx.post(`${WB}/api/v1/waybills`, { headers: disp, data: body() });
    expect(noKind.status()).toBe(422);
    expect(await noKind.text()).toContain('Класс груза (e2e)');
    const badKind = await ctx.post(`${WB}/api/v1/waybills`, { headers: disp, data: body({ [KIND]: 'Средний' }) });
    expect(badKind.status()).toBe(422);
    expect(await badKind.text()).toContain('нет в списке');
    const ok = await ctx.post(`${WB}/api/v1/waybills`, { headers: disp, data: body({ [KIND]: 'Тяжёлый' }) });
    expect(ok.status(), await ok.text()).toBe(201);
    const id = String((await ok.json()).id);
    created.push(id);

    // Карточка листа: значение с подписью из конструктора.
    await page.goto(`/waybills/${id}`);
    await expect(page.getByText('Класс груза (e2e)')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText('Тяжёлый')).toBeVisible();
  });

  test('мастер ПЛ показывает поле на шаге 3 (скрытое — нет), на таджикском — таджикская подпись', async ({ page }) => {
    await loginAs(page, 'dispatcher');
    await page.goto('/waybills/new');
    // Черновик прошлого прогона, если остался, — начать заново.
    const fresh = page.getByRole('button', { name: /Начать заново/ });
    if (await fresh.isVisible().catch(() => false)) await fresh.click();
    await page.getByRole('button', { name: /Спецтехника/ }).first().click();
    await page.getByRole('button', { name: 'Далее' }).click();
    await page.getByPlaceholder(/Введите госномер ТС/).fill('9919QA01');
    await page.getByText('9919QA01', { exact: true }).first().click();
    await page.getByPlaceholder(/Введите ИНН или Ф\.И\.О/).fill('990000009');
    await page.getByText(/990000009/).first().click();
    await page.getByRole('button', { name: 'Далее' }).click();
    await expect(page.getByText('Класс груза (e2e) — обязателен')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText('Примечание (e2e)')).toHaveCount(0);
    const kind = page.locator('label', { hasText: 'Класс груза (e2e)' }).locator('xpath=following-sibling::select[1]');
    await expect(kind.locator('option')).toHaveText(['—', 'Лёгкий', 'Тяжёлый']);
    await page.getByRole('button', { name: 'TJ', exact: true }).click();
    await expect(page.getByText('Синфи бор (e2e) — ҳатмӣ')).toBeVisible();
    await page.getByRole('button', { name: 'RU', exact: true }).click();
  });
});
