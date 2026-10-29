import { test, expect, request, type Page } from '@playwright/test';

/**
 * Настройки → Поля форм (решение владельца 24.09.2026): администратор скрывает поле формы или
 * делает его обязательным без изменения кода. Проверка на живом стенде: настройка сохраняется
 * через API, форма «Добавить организацию» перестраивается, после теста настройка сбрасывается.
 * Пароль администратора — scripts/demo-credentials.ps1 (или E2E_ADMIN_PASSWORD).
 */

const MD = process.env.E2E_MD_URL || 'http://localhost:8081';
// У `admin` обязателен второй фактор (код из телефона) — автотест входит дублем без него.
const ADMIN = process.env.E2E_ADMIN_USER || 'admin-automation';
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD || 'Epd-Qa-Automation-Admin-2026';

async function setSetting(category: string, key: string, value: string) {
  const ctx = await request.newContext();
  const tok = await ctx.post(`${MD}/api/v1/auth/token`, { data: { username: ADMIN, password: ADMIN_PASSWORD } });
  expect(tok.ok()).toBeTruthy();
  const { access_token } = await tok.json();
  const r = await ctx.post(`${MD}/api/v1/settings`, {
    headers: { Authorization: `Bearer ${access_token}` },
    data: { category, settingKey: key, value },
  });
  expect(r.status()).toBe(200);
  await ctx.dispose();
}
const setForm = (form: string, value: string) => setSetting('forms', form, value);
const setOrganizationForm = (value: string) => setForm('organization', value);

/** Текущие значения настроек категории — чтобы после теста вернуть стенд как был (например, V80). */
async function readSettings(category: string): Promise<Record<string, string>> {
  const ctx = await request.newContext();
  const tok = await ctx.post(`${MD}/api/v1/auth/token`, { data: { username: ADMIN, password: ADMIN_PASSWORD } });
  const { access_token } = await tok.json();
  const r = await ctx.get(`${MD}/api/v1/settings?category=${category}`, { headers: { Authorization: `Bearer ${access_token}` } });
  const list = (await r.json()) as { settingKey: string; settingValue: string | null }[];
  await ctx.dispose();
  return Object.fromEntries(list.map(s => [s.settingKey, s.settingValue ?? '']));
}
let savedForms: Record<string, string> = {};
let savedSources: Record<string, string> = {};

async function uiLogin(page: Page) {
  await page.goto('/login');
  await page.getByPlaceholder('Введите логин').fill(ADMIN);
  await page.getByPlaceholder('Введите пароль').fill(ADMIN_PASSWORD);
  await page.getByRole('button', { name: 'Войти', exact: true }).click();
  await page.waitForURL(u => !u.pathname.startsWith('/login') && !u.pathname.startsWith('/auth/'), { timeout: 20_000 });
}

test.describe('Поля форм из настроек', () => {
  test.beforeAll(async () => {
    savedForms = await readSettings('forms');
    savedSources = await readSettings('datasource');
  });
  // Тест меняет настройки стенда — после него возвращаем их к значениям до запуска.
  test.afterAll(async () => {
    for (const f of ['organization', 'driver', 'vehicle', 'employee']) {
      await setForm(f, savedForms[f] ?? '');
      await setSetting('datasource', f, savedSources[f] || 'MANUAL');
    }
  });

  test('справочник ведёт единая платформа: кнопки «Добавить» нет, есть пометка', async ({ page }) => {
    await setSetting('datasource', 'driver', 'UNIFIED');
    await uiLogin(page);
    await page.goto('/fleet/drivers');
    await expect(page.getByText('Добавление — в единой платформе')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Добавить водителя' })).toHaveCount(0);
    // Транспорт остаётся «вручную» — там кнопка на месте.
    await page.goto('/fleet/vehicles');
    await expect(page.getByRole('button', { name: 'Добавить ТС' })).toBeVisible();
    // Раздел «Компания», вкладка водителей: «Прикрепить существующего» тоже скрыта.
    await page.goto('/company');
    await expect(page.getByRole('button', { name: 'Водители', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Прикрепить существующего' })).toHaveCount(0);
    // Блок переключателя на странице интеграций.
    await page.goto('/settings/integrations');
    await expect(page.getByText('Кто ведёт справочники')).toBeVisible();
    await expect(page.getByLabel('Водители')).toHaveValue('UNIFIED');
    await setSetting('datasource', 'driver', 'MANUAL');
  });

  test('водитель в «Парке»: скрытое поле пропадает, обязательное помечено', async ({ page }) => {
    await setForm('driver', '{"passport":"required","medCertNumber":"required","tabNumber":"hidden"}');
    await uiLogin(page);
    await page.goto('/fleet/drivers');
    await page.getByRole('button', { name: 'Добавить водителя' }).click();
    const card = page.locator('.card', { has: page.getByRole('heading', { name: 'Новый водитель' }) });
    await expect(card).toBeVisible();
    await expect(card.locator('label', { hasText: /^Паспорт \(№\) \*$/ })).toHaveCount(1);
    await expect(card.locator('label', { hasText: /^Табельный номер$/ })).toHaveCount(0);
    // Поля, которых раньше в «Парке» не было, теперь есть (общий состав карточки).
    await expect(card.locator('label', { hasText: /^№ договора$/ })).toHaveCount(1);
    await expect(card.locator('details')).toHaveAttribute('open', '');
    await expect(card.locator('label', { hasText: /^Медсправка № \*$/ })).toBeVisible();
  });

  test('дополнительные поля водителя доступны без загромождения основной карточки', async ({ page }) => {
    await setForm('driver', '');
    await uiLogin(page);
    await page.goto('/fleet/drivers');
    await page.getByRole('button', { name: 'Добавить водителя' }).click();
    const card = page.locator('.card', { has: page.getByRole('heading', { name: 'Новый водитель' }) });
    const details = card.locator('details');
    await expect(details).not.toHaveAttribute('open', '');
    await expect(card.locator('label', { hasText: /^Категории ВУ$/ })).toBeVisible();
    await expect(details.getByLabel('Медсправка №')).toBeAttached();
    await details.locator('summary').click();
    await expect(details).toHaveAttribute('open', '');
    await expect(details.getByLabel('Медсправка №')).toBeVisible();
  });

  test('категории ВУ выбираются несколькими отметками', async ({ page }) => {
    await setForm('driver', '');
    await setSetting('datasource', 'driver', 'MANUAL');
    await uiLogin(page);
    await page.goto('/fleet/drivers');
    await page.getByRole('button', { name: 'Добавить водителя' }).click();
    const card = page.locator('.card', { has: page.getByRole('heading', { name: 'Новый водитель' }) });
    const categories = card.getByRole('group', { name: 'Категории ВУ' });
    await categories.getByRole('checkbox', { name: 'B', exact: true }).check();
    await categories.getByRole('checkbox', { name: 'C', exact: true }).check();
    await categories.getByRole('checkbox', { name: 'CE', exact: true }).check();
    await expect(card.getByText('B, C, CE', { exact: true })).toBeVisible();
  });

  test('страница настроек открывает нужную форму по плитке', async ({ page }) => {
    await uiLogin(page);
    await page.goto('/settings/forms?form=vehicle');
    await expect(page.getByRole('heading', { name: 'Поля транспорта' })).toBeVisible();
    await expect(page.getByText('Системное — всегда обязательно')).toHaveCount(2);
    await page.goto('/settings/forms?form=employee');
    await expect(page.getByRole('heading', { name: 'Поля сотрудника' })).toBeVisible();
    await expect(page.getByText('Системное — всегда обязательно')).toHaveCount(3);
  });

  test('скрытое поле пропадает из формы, обязательное помечено и требуется', async ({ page }) => {
    await setOrganizationForm('{"phone":"required","bank":"required","kpp":"hidden"}');
    await uiLogin(page);
    await page.goto('/company');
    await page.getByRole('button', { name: '+ Добавить', exact: true }).first().click();
    const modal = page.locator('.card', { has: page.getByRole('heading', { name: 'Добавить организацию' }) });
    await expect(modal).toBeVisible();

    const phone = modal.locator('div', { has: page.locator('label', { hasText: /^Телефон \*$/ }) }).last();
    await expect(phone.locator('input')).toHaveAttribute('required', '');
    await expect(modal.locator('label', { hasText: /^КПП$/ })).toHaveCount(0);
    await expect(modal.locator('details')).toHaveAttribute('open', '');
    await expect(modal.locator('label', { hasText: /^Банк \*$/ })).toBeVisible();
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
    await expect(modal.locator('label', { hasText: /^Телефон$/ }).locator('xpath=following-sibling::input')).toHaveValue('+992');
    const additional = modal.locator('details');
    await expect(additional).not.toHaveAttribute('open', '');
    await additional.locator('summary').click();
    await expect(additional.locator('label', { hasText: /^Лицензия с$/ })).toBeVisible();
    const subjectType = modal.locator('label', { hasText: /^Тип субъекта$/ }).locator('xpath=following-sibling::select');
    await expect(subjectType).toHaveAttribute('required', '');
    await expect(subjectType).toHaveValue('LEGAL');
    // Пробел между осью и дочерним узлом — невалидный XPath (найдено 28.10.2026); нужен
    // разделитель пути: following-sibling::select/option.
    await expect(modal.locator('label', { hasText: /^Тип компании$/ }).locator('xpath=following-sibling::select/option')).toHaveCount(3);
    await expect(modal.locator('label', { hasText: /^Регион$/ }).locator('xpath=following-sibling::select/option')).toHaveCount(8);
    await expect(modal.locator('label', { hasText: /^Головная организация$/ }).locator('xpath=following-sibling::select')).toBeVisible();
  });

  test('страница настроек показывает поля и закреплённые системные', async ({ page }) => {
    await uiLogin(page);
    await page.goto('/settings/forms');
    await expect(page.getByRole('heading', { name: 'Поля компании (организации)' })).toBeVisible();
    await expect(page.getByText('Системное — всегда обязательно')).toHaveCount(2);
    await expect(page.locator('tbody tr')).toHaveCount(30);
  });
});
