import { test, expect, request, type APIRequestContext, type Page } from '@playwright/test';
import { createHmac } from 'crypto';

/**
 * Второй фактор входа (TOTP, находка 27 AUDIT.md) — весь путь в браузере на живом стенде:
 * подключение по QR-коду → вход с кодом → неверный код → сброс администратором → подключение
 * заново. Для проверки заводится отдельная временная учётка (после теста удаляется), демо-логины
 * не трогаются.
 */

const MD = process.env.E2E_MD_URL || 'http://localhost:8081';
const ADMIN = process.env.E2E_ADMIN_USER || 'admin-automation';
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD || 'Epd-Qa-Automation-Admin-2026';
const COMPANY = 'company';
const COMPANY_PASSWORD = 'Epd-Qa-CompanyAdm-2026';
const PASSWORD = 'E2e-Second-Factor-2026';

function base32Decode(s: string): Buffer {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = '';
  for (const c of s.replace(/\s/g, '').toUpperCase()) bits += alphabet.indexOf(c).toString(2).padStart(5, '0');
  const out: number[] = [];
  for (let i = 0; i + 8 <= bits.length; i += 8) out.push(parseInt(bits.slice(i, i + 8), 2));
  return Buffer.from(out);
}

/** Код RFC 6238 (SHA-1, 6 цифр, 30 с) — то же, что покажет телефон. */
function totp(secret: string, offset = 0): string {
  const step = Math.floor(Date.now() / 1000 / 30) + offset;
  const msg = Buffer.alloc(8);
  msg.writeBigUInt64BE(BigInt(step));
  const h = createHmac('sha1', base32Decode(secret)).update(msg).digest();
  const o = h[h.length - 1] & 0xf;
  const bin = ((h[o] & 0x7f) << 24) | (h[o + 1] << 16) | (h[o + 2] << 8) | h[o + 3];
  return String(bin % 1_000_000).padStart(6, '0');
}

async function bearer(ctx: APIRequestContext, username: string, password: string) {
  const r = await ctx.post(`${MD}/api/v1/auth/token`, { data: { username, password } });
  expect(r.status(), `вход ${username}`).toBe(200);
  return String((await r.json()).access_token);
}

function claims(jwt: string): Record<string, unknown> {
  return JSON.parse(Buffer.from(jwt.split('.')[1], 'base64url').toString('utf8'));
}

async function passwordStep(page: Page, username: string) {
  await page.goto('/login');
  await page.getByPlaceholder('Введите логин').fill(username);
  await page.getByPlaceholder('Введите пароль').fill(PASSWORD);
  await page.getByRole('button', { name: 'Войти', exact: true }).click();
  await expect(page.getByTestId('mfa-form')).toBeVisible({ timeout: 15_000 });
}

async function submitCode(page: Page, code: string) {
  await page.locator('#mfa-code').fill(code);
  await page.getByRole('button', { name: 'Подтвердить', exact: true }).click();
}

test.describe('Второй фактор входа', () => {
  let ctx: APIRequestContext;
  let adminH: Record<string, string>;
  let userId = '';
  const username = `e2e-2fa-${Date.now() % 1_000_000}`;

  test.beforeAll(async () => {
    ctx = await request.newContext();
    adminH = { Authorization: `Bearer ${await bearer(ctx, ADMIN, ADMIN_PASSWORD)}` };
    // Временная учётка диспетчера в демо-компании: создать → сменить временный пароль →
    // включить обязательный второй фактор.
    const org = String(claims(await bearer(ctx, COMPANY, COMPANY_PASSWORD)).organization_rma);
    const created = await ctx.post(`${MD}/api/v1/org-users`, {
      headers: adminH,
      data: { username, lastName: 'E2E', firstName: '2FA', organizationRma: org, role: 'DISPATCHER' },
    });
    expect(created.status()).toBe(201);
    const view = await created.json();
    userId = view.id;
    const ch = await ctx.post(`${MD}/api/v1/auth/token`, { data: { username, password: view.temporaryPassword } });
    expect(ch.status()).toBe(428);
    const changed = await ctx.post(`${MD}/api/v1/auth/password`, {
      data: { changeToken: (await ch.json()).changeToken, newPassword: PASSWORD },
    });
    expect(changed.status()).toBe(204);
    const req = await ctx.patch(`${MD}/api/v1/org-users/${userId}/second-factor`, { headers: adminH, data: { required: true } });
    expect(req.status()).toBe(200);
    expect((await req.json()).secondFactorRequired).toBe(true);
  });

  test.afterAll(async () => {
    if (userId) await ctx.delete(`${MD}/api/v1/org-users/${userId}`, { headers: adminH });
    await ctx.dispose();
  });

  test('подключение по QR, вход с кодом, неверный код, сброс администратором', async ({ page }) => {
    // 1. Первый вход: пароль верен → экран подключения с QR-кодом и ключом для ручного ввода.
    await passwordStep(page, username);
    await expect(page.getByTestId('mfa-qr')).toBeVisible();
    const secret = (await page.getByTestId('mfa-secret').innerText()).replace(/\s/g, '');
    expect(secret).toMatch(/^[A-Z2-7]{32}$/);
    // Сессии до кода нет.
    expect(await page.evaluate(() => localStorage.getItem('dts_rt') ?? sessionStorage.getItem('dts_rt'))).toBeNull();

    await submitCode(page, totp(secret));
    await expect(page.locator('.sidebar')).toBeVisible({ timeout: 15_000 });

    // 2. Повторный вход: QR больше не показывается, спрашивается только код.
    await page.getByRole('button', { name: 'Выход', exact: true }).click();
    await page.waitForURL(u => u.pathname.startsWith('/login'), { timeout: 15_000 });
    await passwordStep(page, username);
    await expect(page.getByTestId('mfa-qr')).toHaveCount(0);
    await submitCode(page, '000000');
    await expect(page.locator('.error')).toContainText('Неверный код');
    // Тот же код дважды не принимается — берём код следующего шага (окно ±30 с).
    await submitCode(page, totp(secret, 1));
    await expect(page.locator('.sidebar')).toBeVisible({ timeout: 15_000 });

    // 3. Потерян телефон: администратор сбрасывает второй фактор — при входе снова QR, новый ключ.
    const reset = await ctx.post(`${MD}/api/v1/org-users/${userId}/reset-second-factor`, { headers: adminH });
    expect(reset.status()).toBe(200);
    expect((await reset.json()).secondFactorEnrolled).toBe(false);
    await page.context().clearCookies();
    await page.evaluate(() => { localStorage.clear(); sessionStorage.clear(); });
    await passwordStep(page, username);
    await expect(page.getByTestId('mfa-qr')).toBeVisible();
    const secret2 = (await page.getByTestId('mfa-secret').innerText()).replace(/\s/g, '');
    expect(secret2).not.toBe(secret);
    await submitCode(page, totp(secret2));
    await expect(page.locator('.sidebar')).toBeVisible({ timeout: 15_000 });
  });
});
