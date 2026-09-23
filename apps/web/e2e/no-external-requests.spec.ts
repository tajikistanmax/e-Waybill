import { test, expect, type Page, type Request } from '@playwright/test';

/**
 * Закрытый гос-контур БЕЗ интернета: платформа не должна ни при какой отрисовке страницы
 * обращаться к внешнему хосту — ни за шрифтами (раньше Google Fonts CDN), ни за тайлами карт
 * (раньше OpenStreetMap по умолчанию), ни за чем-либо ещё. Разрешены только запросы на
 * localhost/127.0.0.1 (сам стенд, в т.ч. проксируемые /md-api, /wb-api) и встроенные data:/blob:
 * (например QR/подписи, сгенерированные на клиенте). См. changelog/2026-09-23-локальные-шрифты-и-карты.md
 * — там же список того, что было заменено (Inter/JetBrains Mono → apps/web/public/fonts и
 * apps/public/public/fonts; карта мониторинга без NEXT_PUBLIC_MAP_TILES_URL → офлайн-подложка
 * с контуром границы РТ вместо тайлов OSM).
 *
 * Логины ниже — актуальные демо-пароли (НЕ password=логин — это старая keycloak-конвенция,
 * которая с master-data /api/v1/auth/token уже не проходит), см. scripts/demo-credentials.ps1.
 * Сервис аутентификации ограничивает частоту попыток входа (защита от подбора пароля, ИБ) —
 * поэтому тест намеренно НЕ перебирает все демо-роли подряд (риск временной блокировки
 * демо-учёток перед показом заказчику), а даёт представительное покрытие: админ филиала (самый
 * широкий набор разделов среди «обычных» ролей + карта мониторинга), диспетчер (свой раздел +
 * карта), врач (узкий раздел) — плюс неавторизованная страница входа и анонимный публичный
 * портал проверки ПЛ (apps/public).
 */

const ALLOWED_HOSTS = new Set(['localhost', '127.0.0.1']);

function isAllowedUrl(urlStr: string): boolean {
  // Встроенные в код ресурсы (data:), объекты в памяти (blob:) — не сетевой трафik.
  if (urlStr.startsWith('data:') || urlStr.startsWith('blob:') || urlStr.startsWith('about:')) return true;
  try {
    const u = new URL(urlStr);
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return true; // не HTTP(S) — не наш трафик
    return ALLOWED_HOSTS.has(u.hostname);
  } catch {
    return true;
  }
}

/** Слушает ВСЕ запросы страницы (документ, XHR/fetch, шрифты, стили, картинки, скрипты) с
 *  момента вызова и до конца теста; assertClean() падает со списком нарушителей, если внешние
 *  запросы были. */
function trackRequests(page: Page) {
  const offenders: string[] = [];
  const seen = new Set<string>();
  page.on('request', (req: Request) => {
    const url = req.url();
    if (!isAllowedUrl(url) && !seen.has(url)) {
      seen.add(url);
      offenders.push(`${req.method()} ${url}`);
    }
  });
  return {
    assertClean: () => expect(offenders, `обнаружены запросы на внешние хосты:\n${offenders.join('\n')}`).toEqual([]),
  };
}

async function loginAs(page: Page, username: string, password: string) {
  await page.goto('/login');
  await page.getByPlaceholder('Введите логин').fill(username);
  await page.getByPlaceholder('Введите пароль').fill(password);
  await page.getByRole('button', { name: 'Войти', exact: true }).click();
  await page.waitForURL(url => !url.pathname.startsWith('/login'), { timeout: 20_000 });
  await expect(page.locator('.sidebar')).toBeVisible({ timeout: 15_000 });
}

/** 'networkidle' не годится: часть страниц (дашборд, отчёты, /verify) держит короткий фоновый
 *  polling или незавершённый fetch на «мусорном» jws, и сеть никогда не затихает на 500мс. Вместо
 *  этого ждём событие load и небольшую паузу — этого достаточно, чтобы поймать все запросы
 *  начального рендера (шрифты/CSS/карта грузятся сразу, не через 10+ секунд). */
async function settle(page: Page) {
  await page.waitForLoadState('load');
  await page.waitForTimeout(1200);
}

async function visitAll(page: Page, paths: string[]) {
  for (const path of paths) {
    await page.goto(path);
    await settle(page);
  }
}

test.describe('Нет запросов на внешние хосты (гос-контур без интернета)', () => {
  test('страница входа /login (неавторизованный)', async ({ page }) => {
    const tracker = trackRequests(page);
    await page.goto('/login');
    await settle(page);
    tracker.assertClean();
  });

  test('диспетчер: /dispatcher, /monitoring (карта), /fleet, /dictionaries', async ({ page }) => {
    const tracker = trackRequests(page);
    await loginAs(page, 'dispatcher', 'Epd-Qa-Tanzim-2026');
    await visitAll(page, ['/dispatcher', '/monitoring', '/fleet', '/dictionaries']);
    tracker.assertClean();
  });

  test('админ филиала: /dashboard, /monitoring (карта), /company, /reports/summary', async ({ page }) => {
    const tracker = trackRequests(page);
    await loginAs(page, 'branch', 'Epd-Qa-BranchAdm-2026');
    await visitAll(page, ['/dashboard', '/monitoring', '/company', '/reports/summary']);
    tracker.assertClean();
  });

  test('врач: /med, /med/journal', async ({ page }) => {
    const tracker = trackRequests(page);
    await loginAs(page, 'doctor', 'Epd-Qa-Duxtur-2026');
    await visitAll(page, ['/med', '/med/journal']);
    tracker.assertClean();
  });
});

test.describe('Публичный портал проверки ПЛ (apps/public, анонимный доступ)', () => {
  test('/verify/{jws}', async ({ page }) => {
    const tracker = trackRequests(page);
    const publicBase = process.env.E2E_PUBLIC_BASE_URL || 'http://localhost:3002';
    await page.goto(`${publicBase}/verify/eyJhbGciOiJSUzI1NiJ9.test.test`);
    await settle(page);
    tracker.assertClean();
  });
});
