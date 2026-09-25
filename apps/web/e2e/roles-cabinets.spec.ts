import { test, expect, type Page, type Browser, type APIRequestContext, request as pwRequest } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

/**
 * Роли, личные кабинеты, доступы, компании и филиалы — сквозная живая проверка (23.09.2026).
 *
 * Запуск (стек уже поднят: web :3000, master-data :8081):
 *   cd apps/web; npx playwright test e2e/roles-cabinets.spec.ts
 * Переменные: E2E_BASE_URL (по умолчанию http://localhost:3000), E2E_MD_URL (http://localhost:8081),
 * E2E_SHOTS — папка скриншотов и отчёта (по умолчанию apps/web/e2e-shots).
 *
 * 1) «Новый перевозчик»: администратор платформы находит компанию и выдаёт ей администратора
 *    (логин = телефон, временный пароль показан один раз) → вход → принудительная смена пароля
 *    на /auth/password → администратор компании видит компанию и филиал → выдаёт логины
 *    администратору филиала, диспетчеру, врачу, механику, бухгалтеру, АЗС и водителю (водитель
 *    привязан к записи водителя) → каждый входит, меняет пароль и попадает в СВОЙ кабинет со
 *    своей областью данных → блокировка/включение/сброс пароля/смена роли/удаление →
 *    блокировка после 10 неудач → выход гасит токен обновления → плановое обновление токена.
 * 2) 14 демо-логинов: каждый пункт меню — скриншот, без 4xx/5xx API, без ошибок консоли,
 *    без непереведённых ключей, без пунктов, уводящих обратно (запрет по роли).
 */

const BASE = process.env.E2E_BASE_URL || 'http://localhost:3000';
const MD = process.env.E2E_MD_URL || 'http://localhost:8081';
const SHOTS = process.env.E2E_SHOTS || path.join(__dirname, '..', 'e2e-shots');
fs.mkdirSync(SHOTS, { recursive: true });

/**
 * Пароли демо-логинов — те же, что в scripts/demo-credentials.ps1. У admin / inspector / analyst
 * обязателен второй фактор (код из телефона, находка 27) — автотест входит их дублями
 * `*-automation` той же роли без второго фактора (см. LOGIN).
 */
const DEMO: Record<string, string> = {
  admin: 'Epd-Qa-Automation-Admin-2026', company: 'Epd-Qa-CompanyAdm-2026', branch: 'Epd-Qa-BranchAdm-2026',
  dispatcher: 'Epd-Qa-Tanzim-2026', doctor: 'Epd-Qa-Duxtur-2026', mechanic: 'Epd-Qa-Mexanik-2026',
  driver: 'Epd-Qa-Ronanda-2026', accountant: 'Epd-Qa-Buxgalter-2026', inspector: 'Epd-Qa-Automation-Inspector-2026',
  analyst: 'Epd-Qa-Automation-Analyst-2026', fuel: 'Epd-Qa-FuelStation-2026', sender: 'Epd-Qa-Sender-2026',
  forwarder: 'Epd-Qa-Forwarder-2026', customs: 'Epd-Qa-Customs-2026',
};
const SECOND_FACTOR_ROLES = new Set(['admin', 'inspector', 'analyst']);
/** Логин, которым автотест входит за роль `who`. */
const LOGIN = (who: string) => (SECOND_FACTOR_ROLES.has(who) ? `${who}-automation` : who);
/** Стартовая страница роли (матрица role_access / roleHome). */
const HOME: Record<string, string> = {
  admin: '/dashboard', company: '/dashboard', branch: '/dashboard', dispatcher: '/dispatcher',
  doctor: '/med', mechanic: '/tech', driver: '/driver', accountant: '/reports', inspector: '/inspector',
  analyst: '/dashboard', fuel: '/fuel', sender: '/consignments', forwarder: '/consignments', customs: '/consignments',
};

// Ключи словаря — чтобы найти на странице непереведённые (t() возвращает сам ключ).
const I18N_FILE = path.join(__dirname, '..', '..', '..', 'packages', 'shared', 'lib', 'i18n.tsx');
const KEY_PREFIXES = (() => {
  try {
    const src = fs.readFileSync(I18N_FILE, 'utf8');
    const set = new Set<string>();
    for (const m of src.matchAll(/^\s+'([a-z][a-zA-Z0-9]*)\.[a-zA-Z0-9_.]+':\s*\{/gm)) set.add(m[1]);
    return [...set];
  } catch { return ['nav', 'role', 'access', 'pwd']; }
})();
const RAW_KEY = new RegExp(`(^|[\\s«"(])((?:${KEY_PREFIXES.join('|')})\\.[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)*)(?=$|[\\s»")\\.,:])`, 'm');

type Issue = { who: string; page: string; kind: string; detail: string };
const issues: Issue[] = [];
const note = (i: Issue) => { issues.push(i); console.log(`[ISSUE] ${i.who} ${i.page} ${i.kind}: ${i.detail}`); };

test.afterAll(() => {
  fs.writeFileSync(path.join(SHOTS, 'roles-cabinets-report.json'), JSON.stringify(issues, null, 2), 'utf8');
});

// ---------------------------------------------------------------------------------------------

async function api(): Promise<APIRequestContext> {
  return pwRequest.newContext({ baseURL: MD });
}

async function token(ctx: APIRequestContext, username: string, password: string): Promise<{ status: number; body: Record<string, unknown> }> {
  const r = await ctx.post('/api/v1/auth/token', { data: { username, password } });
  return { status: r.status(), body: await r.json().catch(() => ({})) };
}

async function bearer(ctx: APIRequestContext, username: string, password: string): Promise<string> {
  const t = await token(ctx, username, password);
  expect(t.status, `вход ${username}`).toBe(200);
  return String(t.body.access_token);
}

function claims(jwt: string): Record<string, unknown> {
  return JSON.parse(Buffer.from(jwt.split('.')[1], 'base64url').toString('utf8'));
}

/** Слежка за страницей: ошибки API и консоли, привязанные к текущему пункту. */
function watch(page: Page, who: string) {
  const state = { current: '' };
  page.on('response', r => {
    const u = r.url();
    if (!(u.includes('/md-api/') || u.includes('/wb-api/'))) return;
    if (r.status() < 400) return;
    // 428 на входе — это и есть «временный пароль, смените его»: ожидаемый ответ, не ошибка.
    if (r.status() === 428 && u.includes('/auth/token')) return;
    // Фон страницы входа из настроек бренда: 404 = «не задан, берётся стандартный» — так задумано.
    if (u.includes('/branding/')) return;
    note({ who, page: state.current, kind: `HTTP ${r.status()}`, detail: `${r.request().method()} ${u.replace(BASE, '')}` });
  });
  page.on('console', m => {
    if (m.type() !== 'error') return;
    const txt = m.text();
    // Отказ загрузки ресурса дублирует уже учтённый HTTP-ответ выше.
    if (/Failed to load resource/.test(txt)) return;
    note({ who, page: state.current, kind: 'console', detail: txt.slice(0, 300) });
  });
  page.on('pageerror', e => note({ who, page: state.current, kind: 'pageerror', detail: String(e).slice(0, 300) }));
  return state;
}

async function uiLogin(page: Page, username: string, password: string) {
  await page.goto('/login');
  await page.getByPlaceholder('Введите логин').fill(username);
  await page.getByPlaceholder('Введите пароль').fill(password);
  await page.getByRole('button', { name: 'Войти', exact: true }).click();
}

async function settle(page: Page) {
  await page.waitForLoadState('domcontentloaded');
  await expect(page.locator('.boot')).toHaveCount(0, { timeout: 20_000 }).catch(() => {});
  await page.waitForTimeout(1200);
}

async function checkRawKeys(page: Page, who: string, where: string) {
  const text = await page.locator('body').innerText().catch(() => '');
  const m = RAW_KEY.exec(text);
  if (m) note({ who, page: where, kind: 'i18n', detail: `непереведённый ключ «${m[2]}»` });
}

/** Первый вход с временным паролем: смена на /auth/password и вход в свой кабинет. */
async function firstLogin(browser: Browser, username: string, temp: string, newPassword: string, who: string) {
  const ctx = await browser.newContext({ baseURL: BASE });
  const page = await ctx.newPage();
  const st = watch(page, who);
  st.current = '/auth/password';
  await uiLogin(page, username, temp);
  await page.waitForURL(u => u.pathname === '/auth/password', { timeout: 15_000 });
  await page.screenshot({ path: path.join(SHOTS, `new-${who}-password.png`) });
  await page.locator('#pwd-new').fill(newPassword);
  await page.locator('#pwd-repeat').fill(newPassword);
  await page.getByRole('button', { name: 'Сохранить пароль' }).click();
  // После смены — сразу вход новым паролем и переход в кабинет роли.
  await page.waitForURL(u => !u.pathname.startsWith('/auth/') && !u.pathname.startsWith('/login'), { timeout: 20_000 });
  await expect(page.locator('.sidebar')).toBeVisible({ timeout: 15_000 });
  await settle(page);
  return { ctx, page, st };
}

async function sidebarHrefs(page: Page): Promise<string[]> {
  const toggle = page.locator('.side-nav button.snav');
  if (await toggle.count()) {
    const cls = await toggle.first().getAttribute('class');
    if (!cls?.includes('open')) await toggle.first().click();
  }
  const hrefs = await page.locator('.side-nav a[href]').evaluateAll(els => els.map(e => e.getAttribute('href') || ''));
  return [...new Set(hrefs.filter(Boolean))];
}

// ---------------------------------------------------------------------------------------------
// 1. Новый перевозчик
// ---------------------------------------------------------------------------------------------

const CO = '990000500';
const BR = '990000501';
const DRIVER_RMA = '990000509';
const NEWPASS = 'E2e-Perevozchik-2026!';
const U = {
  company: '992990500001', branch: '992990500002', dispatcher: '992990500003', doctor: '992990500004',
  mechanic: '992990500005', accountant: '992990500006', fuel: '992990500007', driver: '992990500008',
};

test.describe.serial('новый перевозчик', () => {
  const temp: Record<string, string> = {};
  let md: APIRequestContext;
  let adminJwt = '';

  test.beforeAll(async () => {
    md = await api();
    adminJwt = await bearer(md, LOGIN('admin'), DEMO.admin);
    const h = { Authorization: `Bearer ${adminJwt}` };
    // Компания и филиал — как их заводит администратор платформы (идемпотентно).
    for (const o of [
      { rma: CO, name: 'ООО «Е2Е Перевозчик» (автотест)', typeCompany: 1, regionId: 1, cityName: 'Душанбе', ownership: 2 },
      { rma: BR, parentRma: CO, name: 'Е2Е Перевозчик — филиал «Восток» (автотест)', typeCompany: 1, regionId: 1, cityName: 'Душанбе', ownership: 2 },
    ]) {
      const r = await md.post('/api/v1/organizations', { headers: h, data: o });
      expect([200, 201], `организация ${o.rma}`).toContain(r.status());
    }
    // Хвосты прошлого прогона: учётки с логинами сценария.
    for (const org of [CO, BR]) {
      const list = await (await md.get(`/api/v1/org-users?organizationRma=${org}`, { headers: h })).json();
      for (const u of list as { id: string; username: string }[]) {
        if (u.username.startsWith('9929905000')) await md.delete(`/api/v1/org-users/${u.id}`, { headers: h });
      }
    }
  });

  test('администратор платформы выдаёт компании администратора', async ({ page }) => {
    const st = watch(page, 'admin');
    await uiLogin(page, LOGIN('admin'), DEMO.admin);
    await expect(page.locator('.sidebar')).toBeVisible({ timeout: 15_000 });
    st.current = '/company/access';
    await page.getByRole('link', { name: 'Доступы' }).click();
    await page.waitForURL('**/company/access');
    await settle(page);
    await page.getByLabel('Поиск по названию или РМА').fill(CO);
    await expect(page.getByLabel('Организация', { exact: true })).toHaveValue(CO);
    await page.locator('#acc-username').fill(U.company);
    await page.locator('#acc-fullname').fill('Каримов Фаррух');
    await page.locator('#acc-org').selectOption(CO);
    await page.locator('#acc-role').selectOption('COMPANY_ADMIN');
    await page.getByRole('button', { name: 'Выдать доступ' }).click();
    const banner = page.locator('.card', { hasText: 'Временный пароль для' });
    await expect(banner).toBeVisible();
    temp.company = (await banner.locator('.number').innerText()).trim();
    expect(temp.company.length).toBeGreaterThanOrEqual(12);
    await page.screenshot({ path: path.join(SHOTS, 'new-admin-grants-company.png'), fullPage: true });
    await expect(page.locator('tr', { hasText: U.company })).toContainText('Администратор компании');
  });

  test('администратор компании: смена пароля, компания и филиал, выдача логинов', async ({ browser }) => {
    // Запись водителя в компании — к ней будет привязан логин водителя (перевозчик ведёт своих водителей сам).
    const coJwtTemp = await token(md, U.company, temp.company);
    expect(coJwtTemp.status, 'временный пароль не пускает в систему').toBe(428);

    const { ctx, page, st } = await firstLogin(browser, U.company, temp.company, NEWPASS, 'company-new');
    expect(new URL(page.url()).pathname).toBe('/dashboard');
    const coJwt = await bearer(md, U.company, NEWPASS);
    const d = await md.post('/api/v1/drivers', {
      headers: { Authorization: `Bearer ${coJwt}` },
      data: { rma: DRIVER_RMA, organizationRma: CO, fullName: 'Назаров Сино Ҳамидович', phone: U.driver, licenseNumber: 'AA 1234567', licenseCategories: 'B,C,D', licenseValidTo: '2030-01-01', medCertValidTo: '2027-01-01' },
    });
    expect([200, 201], 'компания добавляет своего водителя').toContain(d.status());

    // Компания видит себя и филиал; переключатель филиала в шапке.
    const orgs = await (await md.get('/api/v1/organizations', { headers: { Authorization: `Bearer ${coJwt}` } })).json() as { rma: string }[];
    expect(orgs.map(o => o.rma).sort()).toEqual([CO, BR]);
    await expect(page.locator('select.branch-switch')).toBeVisible();
    await expect(page.locator('select.branch-switch option')).toHaveCount(3);
    await page.screenshot({ path: path.join(SHOTS, 'new-company-dashboard.png'), fullPage: true });

    st.current = '/company/access';
    await page.getByRole('link', { name: 'Доступы' }).click();
    await page.waitForURL('**/company/access');
    await settle(page);
    // Администратор компании не выдаёт администратора компании и таможенника.
    const roleOpts = await page.locator('#acc-role option').evaluateAll(els => els.map(e => (e as HTMLOptionElement).value));
    expect(roleOpts).not.toContain('COMPANY_ADMIN');
    expect(roleOpts).not.toContain('CUSTOMS_OFFICER');
    expect(roleOpts).toContain('BRANCH_ADMIN');

    const grants: { key: keyof typeof U; role: string; org: string; name: string; person?: string }[] = [
      { key: 'branch', role: 'BRANCH_ADMIN', org: BR, name: 'Сафаров Ҷамшед' },
      { key: 'dispatcher', role: 'DISPATCHER', org: CO, name: 'Назарова Мунира' },
      { key: 'doctor', role: 'DOCTOR', org: CO, name: 'Раҳимова Сурайё' },
      { key: 'mechanic', role: 'MECHANIC', org: CO, name: 'Қосимов Фаррух' },
      { key: 'accountant', role: 'ACCOUNTANT', org: CO, name: 'Шарипова Гулнора' },
      { key: 'fuel', role: 'FUEL_STATION', org: CO, name: 'Саидов Бахтиёр' },
      { key: 'driver', role: 'DRIVER', org: CO, name: '', person: DRIVER_RMA },
    ];
    for (const g of grants) {
      if (g.person) {
        await page.locator('#acc-person').selectOption(g.person);
        // Выбор записи водителя подставляет ФИО и телефон как логин.
        await expect(page.locator('#acc-username')).toHaveValue(U.driver);
      } else {
        await page.locator('#acc-username').fill(U[g.key]);
        await page.locator('#acc-fullname').fill(g.name);
      }
      await page.locator('#acc-org').selectOption(g.org);
      await page.locator('#acc-role').selectOption(g.role);
      await page.getByRole('button', { name: 'Выдать доступ' }).click();
      const banner = page.locator('.card', { hasText: `Временный пароль для «${U[g.key]}»` });
      await expect(banner, `временный пароль ${g.role}`).toBeVisible();
      temp[g.key] = (await banner.locator('.number').innerText()).trim();
    }
    await page.screenshot({ path: path.join(SHOTS, 'new-company-access.png'), fullPage: true });
    // Водитель привязан к записи водителя (claim rma = РМА водителя).
    const list = await (await md.get('/api/v1/org-users', { headers: { Authorization: `Bearer ${coJwt}` } })).json() as { username: string; rma: string | null; manageable: boolean; roles: string[] }[];
    expect(list.find(u => u.username === U.driver)?.rma).toBe(DRIVER_RMA);
    // Себя администратор компании здесь не блокирует и не удаляет.
    expect(list.find(u => u.username === U.company)?.manageable).toBe(false);
    expect(list.find(u => u.username === U.dispatcher)?.manageable).toBe(true);
    await ctx.close();
  });

  const CABINETS: { key: keyof typeof U; home: string; org: string[]; nav: string[] }[] = [
    { key: 'branch', home: '/dashboard', org: [BR], nav: ['/company/access', '/fleet/vehicles'] },
    { key: 'dispatcher', home: '/dispatcher', org: [CO], nav: ['/dispatcher', '/waybills/new'] },
    { key: 'doctor', home: '/med', org: [CO], nav: ['/med', '/med/journal'] },
    { key: 'mechanic', home: '/tech', org: [CO], nav: ['/tech', '/tech/journal'] },
    { key: 'accountant', home: '/reports', org: [CO], nav: ['/reports/summary'] },
    { key: 'fuel', home: '/fuel', org: [CO], nav: ['/fuel'] },
    { key: 'driver', home: '/driver', org: [CO], nav: ['/driver', '/driver/waybills'] },
  ];

  for (const c of CABINETS) {
    test(`кабинет ${c.key}: первый вход, меню, своя область`, async ({ browser }) => {
      const { ctx, page, st } = await firstLogin(browser, U[c.key], temp[c.key], NEWPASS, `new-${c.key}`);
      expect(new URL(page.url()).pathname.startsWith(c.home), `стартовая ${c.home}`).toBeTruthy();
      const hrefs = await sidebarHrefs(page);
      for (const n of c.nav) expect(hrefs, `пункт меню ${n}`).toContain(n);
      // Лишнего нет: чужие кабинеты и платформенные разделы.
      for (const n of ['/settings', '/company']) {
        if (!(c.key === 'branch' && n === '/company')) expect(hrefs).not.toContain(n);
      }
      for (const h of hrefs) {
        st.current = h;
        await page.locator(`.side-nav a[href="${h}"]`).first().click();
        await page.waitForTimeout(300);
        await settle(page);
        const p = new URL(page.url()).pathname;
        if (!p.startsWith(h)) note({ who: `new-${c.key}`, page: h, kind: 'redirect', detail: `пункт меню увёл на ${p}` });
        await checkRawKeys(page, `new-${c.key}`, h);
        await page.screenshot({ path: path.join(SHOTS, `new-${c.key}${h.replace(/\//g, '_')}.png`), fullPage: true });
      }
      // Область данных — только своя организация / филиал.
      const jwt = await bearer(md, U[c.key], NEWPASS);
      const cl = claims(jwt);
      expect(cl.organization_rma).toBe(c.org[0]);
      if (c.key === 'driver') {
        expect(cl.rma, 'логин водителя привязан к записи водителя').toBe(DRIVER_RMA);
        await page.locator('.side-nav a[href="/driver"]').click();
        await settle(page);
        await expect(page.locator('main')).toContainText('Назаров');
      }
      if (c.key === 'branch' || c.key === 'dispatcher') {
        const orgs = await (await md.get('/api/v1/organizations', { headers: { Authorization: `Bearer ${jwt}` } })).json() as { rma: string }[];
        expect(orgs.map(o => o.rma)).toEqual(c.org);
      }
      await ctx.close();
    });
  }

  test('управление учётками: блокировка, включение, сброс, смена роли, удаление', async ({ browser }) => {
    const ctx = await browser.newContext({ baseURL: BASE });
    const page = await ctx.newPage();
    watch(page, 'company-new').current = '/company/access';
    await uiLogin(page, U.company, NEWPASS);
    await expect(page.locator('.sidebar')).toBeVisible({ timeout: 15_000 });
    await page.goto('/company/access');
    await settle(page);
    page.on('dialog', d => d.accept());
    const row = () => page.locator('tr', { hasText: U.accountant });

    await row().getByRole('button', { name: 'Отключить' }).click();
    await expect(row()).toContainText('отключён');
    expect((await token(md, U.accountant, NEWPASS)).status, 'отключённый не входит').toBe(403);
    await row().getByRole('button', { name: 'Включить' }).click();
    await expect(row()).toContainText('активен');
    expect((await token(md, U.accountant, NEWPASS)).status, 'включённый входит').toBe(200);

    await row().getByRole('button', { name: 'Сбросить пароль' }).click();
    const banner = page.locator('.card', { hasText: `Временный пароль для «${U.accountant}»` });
    await expect(banner).toBeVisible();
    const t2 = (await banner.locator('.number').innerText()).trim();
    expect((await token(md, U.accountant, NEWPASS)).status, 'старый пароль после сброса').toBe(401);
    expect((await token(md, U.accountant, t2)).status, 'новый временный — снова смена').toBe(428);

    await row().getByLabel('Роль', { exact: true }).selectOption('MECHANIC');
    await expect(row().getByLabel('Роль', { exact: true })).toHaveValue('MECHANIC');
    const ch = (await (await md.post('/api/v1/auth/token', { data: { username: U.accountant, password: t2 } })).json()) as { changeToken: string };
    expect((await md.post('/api/v1/auth/password', { data: { changeToken: ch.changeToken, newPassword: NEWPASS } })).status()).toBe(204);
    const roles = (claims(await bearer(md, U.accountant, NEWPASS)).realm_access as { roles: string[] }).roles;
    expect(roles).toEqual(['MECHANIC']);

    await row().getByRole('button', { name: 'Удалить' }).click();
    await expect(row()).toHaveCount(0);
    expect((await token(md, U.accountant, NEWPASS)).status, 'удалённый не входит').toBe(401);
    await page.screenshot({ path: path.join(SHOTS, 'new-company-manage.png'), fullPage: true });
    await ctx.close();
  });

  test('блокировка после 10 неудачных входов', async () => {
    for (let i = 0; i < 10; i++) expect((await token(md, U.fuel, `wrong-${i}`)).status).toBe(401);
    expect((await token(md, U.fuel, NEWPASS)).status, 'даже верный пароль — временно нет').toBe(429);
  });

  test('выход гасит токен обновления; плановое обновление токена в браузере', async ({ browser }) => {
    const ctx = await browser.newContext({ baseURL: BASE });
    const page = await ctx.newPage();
    watch(page, 'refresh');
    await page.clock.install();
    await uiLogin(page, U.mechanic, NEWPASS);
    await expect(page.locator('.sidebar')).toBeVisible({ timeout: 15_000 });
    const rt1 = await page.evaluate(() => localStorage.getItem('dts_rt'));
    expect(rt1).toBeTruthy();
    // Токен доступа живёт 30 минут; к 29-й минуте интерфейс сам обменивает токен обновления.
    const refreshed = page.waitForResponse(r => r.url().includes('/auth/refresh'), { timeout: 20_000 });
    await page.clock.fastForward('29:30');
    expect((await refreshed).status()).toBe(200);
    const rt2 = await page.evaluate(() => localStorage.getItem('dts_rt'));
    expect(rt2).not.toBe(rt1);
    // Прежний токен обновления одноразовый — повтор отвергается.
    expect((await md.post('/api/v1/auth/refresh', { data: { refreshToken: rt1 } })).status()).toBe(401);
    // Выход — серверный отзыв: сохранённый токен обновления больше не принимается.
    await page.getByRole('button', { name: 'Выход', exact: true }).click();
    await page.waitForURL('**/login');
    await page.waitForTimeout(500);
    expect((await md.post('/api/v1/auth/refresh', { data: { refreshToken: rt2 } })).status(), 'после выхода').toBe(401);
    await ctx.close();
  });

  test.afterAll(async () => {
    // Уборка: учётки сценария и запись водителя; организации удаляются, если пусты.
    const h = { Authorization: `Bearer ${await bearer(md, LOGIN('admin'), DEMO.admin)}` };
    for (const org of [CO, BR]) {
      const list = await (await md.get(`/api/v1/org-users?organizationRma=${org}`, { headers: h })).json() as { id: string; username: string }[];
      for (const u of list) if (u.username.startsWith('9929905000')) await md.delete(`/api/v1/org-users/${u.id}`, { headers: h });
    }
    const drv = await (await md.get(`/api/v1/drivers?rma=${DRIVER_RMA}`, { headers: h })).json() as { id: string }[];
    for (const d of drv) await md.delete(`/api/v1/drivers/${d.id}`, { headers: h });
    const orgs = await (await md.get('/api/v1/organizations', { headers: h })).json() as { id: string; rma: string }[];
    for (const rma of [BR, CO]) {
      const o = orgs.find(x => x.rma === rma);
      if (o) await md.delete(`/api/v1/organizations/${o.id}`, { headers: h });
    }
  });
});

// ---------------------------------------------------------------------------------------------
// 2. Все 14 демо-логинов: каждый пункт меню
// ---------------------------------------------------------------------------------------------

test.describe('кабинеты демо-логинов', () => {
  for (const who of Object.keys(DEMO)) {
    test(`${who}: меню и страницы`, async ({ page }) => {
      test.setTimeout(240_000);
      const st = watch(page, who);
      st.current = '/login';
      await uiLogin(page, LOGIN(who), DEMO[who]);
      await page.waitForURL(u => !u.pathname.startsWith('/login'), { timeout: 20_000 });
      await expect(page.locator('.sidebar')).toBeVisible({ timeout: 15_000 });
      await settle(page);
      const landed = new URL(page.url()).pathname;
      expect(landed.startsWith(HOME[who]), `${who}: стартовая ${HOME[who]}, пришёл на ${landed}`).toBeTruthy();
      const hrefs = await sidebarHrefs(page);
      fs.writeFileSync(path.join(SHOTS, `menu-${who}.json`), JSON.stringify(hrefs), 'utf8');
      for (const h of hrefs) {
        st.current = h;
        await page.locator(`.side-nav a[href="${h}"]`).first().click();
        await page.waitForTimeout(300);
        await settle(page);
        const p = new URL(page.url()).pathname;
        if (!p.startsWith(h)) note({ who, page: h, kind: 'redirect', detail: `пункт меню увёл на ${p}` });
        await checkRawKeys(page, who, h);
        await page.screenshot({ path: path.join(SHOTS, `${who}${h.replace(/\//g, '_')}.png`), fullPage: true });
      }
    });
  }

  // Подстраницы разделов, до которых меню ведёт не напрямую (вкладки настроек, справочников,
  // реестров, отчётов, парка): открыть каждую, без ошибок API/консоли и сырых ключей.
  const SUBPAGES: Record<string, string[]> = {
    admin: [
      '/settings/general', '/settings/interface', '/settings/branding', '/settings/security', '/settings/roles',
      '/settings/types', '/settings/statuses', '/settings/fields', '/settings/classifiers', '/settings/numbering',
      '/settings/policies', '/settings/expiry', '/settings/notifications', '/settings/integrations', '/settings/print',
      '/settings/print/templates', '/settings/reports', '/settings/performance', '/settings/audit', '/settings/backup',
      '/dictionaries/routes', '/dictionaries/route-types', '/dictionaries/route-tariffs', '/dictionaries/tariffs',
      '/dictionaries/clients', '/dictionaries/cargos', '/dictionaries/directions', '/dictionaries/cities',
      '/dictionaries/external-cities', '/dictionaries/brands', '/dictionaries/fuel-norms', '/dictionaries/coefficients',
      '/dictionaries/winter-coefs', '/dictionaries/city-coefs', '/dictionaries/mountain-coefs', '/dictionaries/used-coefs',
      '/dictionaries/drive-classes',
      '/registry/vehicles', '/registry/drivers', '/registry/employees', '/registry/devices',
      '/reports/summary', '/reports/journal', '/reports/by-driver', '/reports/by-vehicle', '/reports/fuel',
      '/reports/passenger', '/reports/cargo', '/reports/malumotnoma', '/reports/journals',
      '/reports/regional', '/reports/regional/count', '/reports/regional/norm', '/reports/regional/plans', '/notifications',
    ],
    company: [
      '/company', '/company/access', '/fleet/vehicles', '/fleet/drivers', '/fleet/employees',
      '/registry/vehicles', '/registry/drivers', '/registry/employees',
      '/dictionaries/routes', '/dictionaries/clients', '/dictionaries/fuel-norms',
      '/reports/summary', '/reports/journal', '/reports/by-driver', '/reports/by-vehicle', '/reports/fuel',
      '/reports/passenger', '/reports/cargo', '/reports/malumotnoma', '/reports/journals', '/notifications',
    ],
    branch: ['/company', '/company/access', '/fleet/vehicles', '/fleet/drivers', '/fleet/employees', '/monitoring', '/reports/summary'],
    dispatcher: ['/fleet/vehicles', '/fleet/drivers', '/fleet/employees', '/waybills/journal'],
    inspector: ['/reports/summary', '/reports/journals'],
    analyst: ['/registry/drivers', '/registry/employees', '/reports/regional', '/reports/regional/count',
      '/reports/regional/norm', '/reports/regional/plans', '/reports/passenger', '/reports/cargo'],
  };
  for (const [who, pages] of Object.entries(SUBPAGES)) {
    test(`${who}: подстраницы разделов`, async ({ page }) => {
      test.setTimeout(420_000);
      const st = watch(page, who);
      await uiLogin(page, LOGIN(who), DEMO[who]);
      await page.waitForURL(u => !u.pathname.startsWith('/login'), { timeout: 20_000 });
      await expect(page.locator('.sidebar')).toBeVisible({ timeout: 15_000 });
      for (const p of pages) {
        st.current = p;
        await page.goto(p);
        await settle(page);
        const landed = new URL(page.url()).pathname;
        if (!landed.startsWith(p)) note({ who, page: p, kind: 'redirect', detail: `открылось ${landed}` });
        const empty = await page.locator('main.page').first().innerText().catch(() => '');
        if (empty.trim().length < 20) note({ who, page: p, kind: 'empty', detail: 'пустая страница' });
        await checkRawKeys(page, who, p);
        await page.screenshot({ path: path.join(SHOTS, `sub-${who}${p.replace(/\//g, '_')}.png`), fullPage: true });
      }
    });
  }

  test('компания ведёт свой парк: водитель в филиал — добавить, найти, удалить', async ({ page }) => {
    test.setTimeout(120_000);
    const st = watch(page, 'company-fleet');
    const RMA = '990000777';
    await uiLogin(page, 'company', DEMO.company);
    await expect(page.locator('.sidebar')).toBeVisible({ timeout: 15_000 });
    st.current = '/fleet/drivers';
    await page.goto('/fleet/drivers');
    await settle(page);
    page.on('dialog', d => d.accept());
    await page.getByRole('button', { name: 'Добавить водителя' }).click();
    // Компания с филиалами: запись можно завести прямо в филиал.
    const orgSel = page.locator('#fleet-org');
    await expect(orgSel).toBeVisible();
    await orgSel.selectOption('100002092');
    await page.locator('#fleet-rma').fill(RMA);
    await page.locator('#fleet-fullName').fill('Тестов Водитель Филиалович');
    await page.locator('#fleet-licenseValidTo').fill('2030-12-31');
    await page.locator('#fleet-medCertValidTo').fill('2026-10-01');
    await page.getByRole('button', { name: 'Добавить', exact: true }).click();
    await expect(page.getByText('Добавлено')).toBeVisible();
    await page.getByPlaceholder('Поиск по ИНН или Ф.И.О…').fill(RMA);
    const row = page.locator('tr', { hasText: 'Тестов Водитель' });
    await expect(row).toBeVisible();
    await page.screenshot({ path: path.join(SHOTS, 'company-fleet-driver-added.png'), fullPage: true });
    // Запись в филиале: администратор филиала её видит, чужой филиал — нет.
    const md = await api();
    const drv = await (await md.get(`/api/v1/drivers?rma=${RMA}`, { headers: { Authorization: `Bearer ${await bearer(md, 'company', DEMO.company)}` } })).json() as { organizationId: string }[];
    expect(drv.length).toBe(1);
    const branchSees = await (await md.get(`/api/v1/drivers?rma=${RMA}`, { headers: { Authorization: `Bearer ${await bearer(md, 'branch', DEMO.branch)}` } })).json() as unknown[];
    expect(branchSees.length, 'водитель филиала «Север» не виден филиалу «Юг»').toBe(0);
    await row.locator('button').last().click();
    await expect(page.getByText('Удалено')).toBeVisible();
    await expect(row).toHaveCount(0);
  });

  test('справочник маршрутов компании: создать, найти, изменить, удалить', async ({ page }) => {
    test.setTimeout(120_000);
    const st = watch(page, 'company-dict');
    const NUM = `E2E${Date.now() % 100000}`;
    await uiLogin(page, 'company', DEMO.company);
    await expect(page.locator('.sidebar')).toBeVisible({ timeout: 15_000 });
    st.current = '/dictionaries/routes';
    await page.goto('/dictionaries/routes');
    await settle(page);
    await page.getByRole('button', { name: 'Добавить' }).click();
    await page.getByPlaceholder('3', { exact: true }).fill(NUM);
    await page.getByPlaceholder('Вокзал — Аэропорт').fill('Автотест — Туда');
    await page.locator('form.grid select').first().selectOption({ index: 1 });
    await page.getByRole('button', { name: 'Сохранить' }).click();
    await expect(page.getByText('Сохранено')).toBeVisible();
    await page.getByPlaceholder('Поиск по справочнику').fill(NUM);
    const row = page.locator('tr', { hasText: NUM });
    await expect(row).toHaveCount(1);
    await row.getByTitle('Изменить').click();
    await page.getByPlaceholder('Вокзал — Аэропорт').fill('Автотест — Обратно');
    await page.getByRole('button', { name: 'Сохранить' }).click();
    await expect(row).toContainText('Автотест — Обратно');
    await expect(page.locator('tr', { hasText: NUM }), 'правка не создаёт дубль').toHaveCount(1);
    await row.getByTitle('Удалить').click();
    await page.getByRole('button', { name: 'Удалить', exact: true }).last().click();
    await expect(page.locator('tr', { hasText: NUM })).toHaveCount(0);
  });

  test('матрица ролей на странице «Роли» меняет меню', async ({ browser }) => {
    test.setTimeout(150_000);
    const md = await api();
    const h = { Authorization: `Bearer ${await bearer(md, LOGIN('admin'), DEMO.admin)}` };
    const before = (await (await md.get('/api/v1/role-access', { headers: h })).json() as { role: string; homeKey: string; navKeys: string[] }[])
      .find(r => r.role === 'ACCOUNTANT')!;
    try {
      const actx = await browser.newContext({ baseURL: BASE });
      const ap = await actx.newPage();
      await uiLogin(ap, LOGIN('admin'), DEMO.admin);
      await expect(ap.locator('.sidebar')).toBeVisible({ timeout: 15_000 });
      await ap.goto('/settings/roles');
      await settle(ap);
      const card = ap.locator('.card', { has: ap.locator('.badge', { hasText: /^ACCOUNTANT$/ }) });
      await card.locator('label', { hasText: 'GPS-мониторинг' }).locator('input').check();
      await card.getByRole('button', { name: 'Сохранить' }).click();
      await expect(card).toContainText('Сохранено');
      const bctx = await browser.newContext({ baseURL: BASE });
      const bp = await bctx.newPage();
      await uiLogin(bp, 'accountant', DEMO.accountant);
      await expect(bp.locator('.sidebar')).toBeVisible({ timeout: 15_000 });
      expect(await sidebarHrefs(bp), 'пункт появился в меню бухгалтера').toContain('/monitoring');
      // Обратно — снять пункт тем же интерфейсом.
      await card.locator('label', { hasText: 'GPS-мониторинг' }).locator('input').uncheck();
      await card.getByRole('button', { name: 'Сохранить' }).click();
      await expect(card).toContainText('Сохранено');
      await bp.reload();
      await expect(bp.locator('.sidebar')).toBeVisible({ timeout: 15_000 });
      expect(await sidebarHrefs(bp), 'пункт исчез из меню бухгалтера').not.toContain('/monitoring');
      await actx.close(); await bctx.close();
    } finally {
      await md.post('/api/v1/role-access', { headers: h, data: { role: 'ACCOUNTANT', homeKey: before.homeKey, navKeys: before.navKeys } });
    }
  });

  test('итог: замечаний по кабинетам нет', () => {
    const mine = issues.filter(i => !i.who.startsWith('new-') && i.who !== 'refresh');
    expect.soft(mine, JSON.stringify(mine, null, 2)).toEqual([]);
  });
});
