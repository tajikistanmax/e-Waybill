import { test, expect } from '@playwright/test';
import { loginAs } from './fixtures';

/**
 * Отчёты в боковом меню (app/reports/nav.ts): группа «Отчёты» (своё предприятие) и отдельная
 * группа «Общие отчёты (Умумӣ)» — сводные Минтранса, legacy /admin/reportwaybillgeneral.
 * Каждый отчёт группы открывается по своему адресу и грузится без ошибки API.
 * E2E_SHOTS=<папка> — дополнительно сохранить снимки страниц.
 */
const GENERAL: { name: string; href: string; marker: RegExp }[] = [
  // Маркер — строка итогов, которая появляется только после ответа API.
  { name: 'Перевозки: план/факт', href: '/reports/regional', marker: /Республика Таджикистан/ },
  { name: 'Количество ПЛ и ТС', href: '/reports/regional/count', marker: /Республика Таджикистан/ },
  { name: 'Норматив выдачи ПЛ', href: '/reports/regional/norm', marker: /ИТОГО/ },
  { name: 'Планы перевозок', href: '/reports/regional/plans', marker: /Плановые показатели/ },
];

for (const who of ['analyst-automation', 'admin-automation']) {
  test(`${who}: группа «Умумӣ» в меню, каждый отчёт по своему адресу`, async ({ page }) => {
    await loginAs(page, who);
    await page.goto('/reports/summary');
    const side = page.locator('.sidebar');

    // Группа Минтранса — отдельный пункт меню, раскрывается и ведёт на свои отчёты.
    await side.getByRole('button', { name: 'Общие отчёты (Умумӣ)' }).click();
    for (const r of GENERAL) {
      await side.getByRole('link', { name: r.name, exact: true }).click();
      await expect(page).toHaveURL(new RegExp(r.href.replace(/\//g, '\\/') + '$'));
      await expect(page.locator('h1')).toHaveText(r.name);
      await expect(side.getByRole('link', { name: r.name, exact: true })).toHaveClass(/active/);
      await expect(page.locator('main')).toContainText(r.marker, { timeout: 15_000 });
      await expect(page.locator('.error')).toHaveCount(0);
      if (process.env.E2E_SHOTS) await page.screenshot({ path: `${process.env.E2E_SHOTS}/${who}${r.href.replace(/\//g, '_')}.png`, fullPage: true });
    }

    // Отчёты предприятия — своя группа: Мусофирбарӣ и Боркашонӣ по отдельным адресам.
    await side.getByRole('link', { name: 'Грузовые (Боркашонӣ)', exact: true }).click();
    await expect(page).toHaveURL(/\/reports\/cargo$/);
    await expect(page.locator('main h2').first()).toContainText('грузовые');
    await side.getByRole('link', { name: 'Пассажирские (Мусофирбарӣ)', exact: true }).click();
    await expect(page).toHaveURL(/\/reports\/passenger$/);
    await expect(page.locator('main h2').first()).toContainText('пассажирские');
    // Каталог типовых отчётов загружен (Автомобил, Табел, Хатсайр …).
    await expect.poll(() => page.locator('main select').first().locator('option').count()).toBeGreaterThan(5);
    await expect(page.locator('.error')).toHaveCount(0);
    if (process.env.E2E_SHOTS) await page.screenshot({ path: `${process.env.E2E_SHOTS}/${who}_reports_passenger.png`, fullPage: true });
  });
}

test('старый адрес /reports/sections ведёт на Мусофирбарӣ', async ({ page }) => {
  await loginAs(page, 'accountant');
  await page.goto('/reports/sections');
  await expect(page).toHaveURL(/\/reports\/passenger$/);
});
