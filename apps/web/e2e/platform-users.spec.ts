import { test, expect, request, type APIRequestContext } from '@playwright/test';
import { login } from './fixtures';

/**
 * «Пользователи» администратора платформы (замена legacy /admin/user) — в браузере на живом
 * стенде: поиск и фильтры по всей платформе, учётка без организации (аналитик, второй фактор
 * обязателен сразу), смена роли с переводом в организацию, отключение, удаление; служебные
 * учётки без действий; администратору компании страница и API закрыты.
 */

const MD = process.env.E2E_MD_URL || 'http://localhost:8081';
const ADMIN = process.env.E2E_ADMIN_USER || 'admin-automation';
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD || 'Epd-Qa-Automation-Admin-2026';
const COMPANY = 'company';
const COMPANY_PASSWORD = 'Epd-Qa-CompanyAdm-2026';

async function bearer(ctx: APIRequestContext, username: string, password: string) {
  const r = await ctx.post(`${MD}/api/v1/auth/token`, { data: { username, password } });
  expect(r.status(), `вход ${username}`).toBe(200);
  return String((await r.json()).access_token);
}

test.describe('Пользователи платформы', () => {
  let ctx: APIRequestContext;
  let adminH: Record<string, string>;
  let companyOrg = '';
  const username = `e2e-pu-${Date.now() % 1_000_000}`;

  test.beforeAll(async () => {
    ctx = await request.newContext();
    adminH = { Authorization: `Bearer ${await bearer(ctx, ADMIN, ADMIN_PASSWORD)}` };
    const companyJwt = await bearer(ctx, COMPANY, COMPANY_PASSWORD);
    companyOrg = String(JSON.parse(Buffer.from(companyJwt.split('.')[1], 'base64url').toString('utf8')).organization_rma);
  });

  test.afterAll(async () => {
    // Если тест упал до удаления — убрать временную учётку.
    const r = await ctx.get(`${MD}/api/v1/platform-users?q=${username}`, { headers: adminH });
    for (const u of (await r.json()).content ?? []) {
      if (u.username === username) await ctx.delete(`${MD}/api/v1/org-users/${u.id}`, { headers: adminH });
    }
    await ctx.dispose();
  });

  test('поиск, создание без организации, смена роли, отключение, удаление', async ({ page }) => {
    page.on('dialog', d => d.accept());
    await login(page, ADMIN, ADMIN_PASSWORD);
    await page.locator('.sidebar').getByRole('link', { name: 'Пользователи', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Пользователи платформы' })).toBeVisible();

    // Учётки без организации — только платформенные (у всех в колонке «Организация» одно и то же).
    await page.getByLabel('Организация', { exact: true }).first().selectOption('__none__');
    const rows = page.locator('table tbody tr');
    // Ждём, пока таблица перерисуется по фильтру (первая строка без фильтра тоже может быть без организации).
    await expect.poll(async () => {
      const cells = await rows.locator('td:nth-child(3)').allInnerTexts();
      return cells.length > 0 && cells.every(c => c.trim() === 'платформа');
    }, { timeout: 10_000 }).toBe(true);
    await page.getByLabel('Организация', { exact: true }).first().selectOption('');

    // Новая учётка аналитика без организации: временный пароль показан, второй фактор обязателен.
    await page.locator('#pu-username').fill(username);
    await page.locator('#pu-fullname').fill('Тестов Аналитик');
    await page.locator('#pu-role').selectOption('MINTRANS_ANALYST');
    await page.getByRole('button', { name: 'Создать', exact: true }).click();
    await expect(page.getByText(`Временный пароль для «${username}»`, { exact: false })).toBeVisible({ timeout: 15_000 });

    await page.getByPlaceholder('Логин, фамилия, имя или РМА').fill(username);
    const row = page.locator('table tbody tr', { hasText: username });
    await expect(row).toHaveCount(1, { timeout: 10_000 });
    await expect(row).toContainText('Аналитик Минтранса');
    await expect(row.locator('td:nth-child(3)')).toHaveText('платформа');
    await expect(row).toContainText('не подключена');
    await expect(row).toContainText('временный пароль');
    await expect(row).toContainText('не входил');

    // Перевод в диспетчеры демо-компании: организация обязательна для роли перевозчика.
    await row.getByLabel('Действие', { exact: true }).selectOption('role');
    await row.getByLabel('Роль', { exact: true }).selectOption('DISPATCHER');
    await row.getByLabel('Организация', { exact: true }).selectOption(companyOrg);
    await row.getByRole('button', { name: 'Сохранить роль', exact: true }).click();
    await expect(row).toContainText('Диспетчер');
    await expect(row.locator('td:nth-child(3)')).not.toHaveText('платформа');

    // Отключение и удаление.
    await row.getByLabel('Действие', { exact: true }).selectOption('toggle');
    await expect(row).toContainText('отключён');
    await row.getByLabel('Действие', { exact: true }).selectOption('delete');
    await expect(page.getByText('Никого не найдено')).toBeVisible({ timeout: 10_000 });
  });

  test('служебные учётки и своя — без действий', async ({ page }) => {
    await login(page, ADMIN, ADMIN_PASSWORD);
    await page.goto('/company/access/users');
    await page.getByLabel('Роль', { exact: true }).first().selectOption('API_INTEGRATOR');
    const rows = page.locator('table tbody tr');
    await expect(rows.first()).toContainText('служебная');
    await expect(rows.getByLabel('Действие', { exact: true })).toHaveCount(0);

    await page.getByLabel('Роль', { exact: true }).first().selectOption('');
    await page.getByPlaceholder('Логин, фамилия, имя или РМА').fill(ADMIN);
    const me = page.locator('table tbody tr', { hasText: 'это вы' });
    await expect(me).toHaveCount(1, { timeout: 10_000 });
    await expect(me.getByLabel('Действие', { exact: true })).toHaveCount(0);
  });

  test('администратору компании закрыто', async ({ page }) => {
    const h = { Authorization: `Bearer ${await bearer(ctx, COMPANY, COMPANY_PASSWORD)}` };
    expect((await ctx.get(`${MD}/api/v1/platform-users`, { headers: h })).status()).toBe(403);
    await login(page, COMPANY, COMPANY_PASSWORD);
    await expect(page.locator('.sidebar').getByRole('link', { name: 'Пользователи', exact: true })).toHaveCount(0);
    await page.goto('/company/access/users');
    await expect(page.getByText('Страница доступна только администратору платформы.')).toBeVisible();
  });
});
