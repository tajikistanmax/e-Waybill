import { test, expect, request, type Page } from '@playwright/test';

/**
 * Настройки → Поля форм (решение владельца 24.09.2026): администратор скрывает поле формы или
 * делает его обязательным без изменения кода. Проверка на живом стенде: настройка сохраняется
 * через API, форма «Добавить организацию» перестраивается, после теста настройка сбрасывается.
 * Пароль администратора — scripts/demo-credentials.ps1 (или E2E_ADMIN_PASSWORD).
 */

const MD = process.env.E2E_MD_URL || 'http://localhost:8081';
const ADMIN = process.env.E2E_ADMIN_USER || 'admin';
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD || 'Epd-Qa-AdminRoot-2026';

async function setOrganizationForm(value: string) {
  const ctx = await request.newContext();
  const tok = await ctx.post(`${MD}/api/v1/auth/token`, { data: { username: ADMIN, password: ADMIN_PASSWORD } });
  expect(tok.ok()).toBeTruthy();
  const { access_token } = await tok.json();
  const r = await ctx.post(`${MD}/api/v1/settings`, {
    headers: { Authorization: `Bearer ${access_token}` },
    data: { category: 'forms', settingKey: 'organization', value },
  });
  expect(r.status()).toBe(200);
  await ctx.dispose();
}

async function uiLogin(page: Page) {
  await page.goto('/login');
  await page.getByPlaceholder('Введите логин').fill(ADMIN);
  await page.getByPlaceholder('Введите пароль').fill(ADMIN_PASSWORD);
  await page.getByRole('button', { name: 'Войти', exact: true }).click();
  await page.waitForURL(u => !u.pathname.startsWith('/login') && !u.pathname.startsWith('/auth/'), { timeout: 20_000 });
}

test.describe('Поля форм из настроек', () => {
  test.afterAll(async () => { await setOrganizationForm(''); });

  test('скрытое поле пропадает из формы, обязательное помечено и требуется', async ({ page }) => {
    await setOrganizationForm('{"phone":"required","kpp":"hidden"}');
    await uiLogin(page);
    await page.goto('/company');
    await page.getByRole('button', { name: '+ Добавить', exact: true }).first().click();
    const modal = page.locator('.card', { has: page.getByRole('heading', { name: 'Добавить организацию' }) });
    await expect(modal).toBeVisible();

    const phone = modal.locator('div', { has: page.locator('label', { hasText: /^Телефон \*$/ }) }).last();
    await expect(phone.locator('input')).toHaveAttribute('required', '');
    await expect(modal.locator('label', { hasText: /^КПП$/ })).toHaveCount(0);
    // Системные поля остаются на месте и обязательны.
    await expect(modal.locator('label', { hasText: 'Название *' })).toHaveCount(1);
  });

  test('без настройки форма прежняя: КПП есть, телефон необязателен', async ({ page }) => {
    await setOrganizationForm('');
    await uiLogin(page);
    await page.goto('/company');
    await page.getByRole('button', { name: '+ Добавить', exact: true }).first().click();
    const modal = page.locator('.card', { has: page.getByRole('heading', { name: 'Добавить организацию' }) });
    await expect(modal.locator('label', { hasText: /^КПП$/ })).toHaveCount(1);
    await expect(modal.locator('label', { hasText: /^Телефон$/ })).toHaveCount(1);
  });

  test('страница настроек показывает поля и закреплённые системные', async ({ page }) => {
    await uiLogin(page);
    await page.goto('/settings/forms');
    await expect(page.getByRole('heading', { name: 'Поля форм' })).toBeVisible();
    await expect(page.getByText('Системное — всегда обязательно')).toHaveCount(2);
    await expect(page.locator('tbody tr')).toHaveCount(30);
  });
});
