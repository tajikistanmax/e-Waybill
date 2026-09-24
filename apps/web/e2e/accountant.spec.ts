import { test, expect } from '@playwright/test';
import { loginAs } from './fixtures';

/**
 * Golden path 2 (accountant): log in, land on /reports/summary, walk every report tab
 * visible to this role and confirm NONE of them 403 — a direct regression test for a
 * real shipped bug where ReportController's class-level @PreAuthorize omitted ACCOUNTANT.
 * Also confirms the accountant cannot create waybills (no nav entry, and a direct visit
 * to /waybills/new shows a clear explanation instead of a raw 403 cascade).
 */

// Tabs visible to ACCOUNTANT per lib/roles.ts canSeeCarrierEconomics/canSeeMintransReports:
// economics tabs ARE visible (accountant is in the allow-list), mintrans (regional) is NOT.
const EXPECTED_TABS: { name: string; href: string }[] = [
  { name: 'Сводка', href: '/reports/summary' },
  { name: 'Журнал диспетчера', href: '/reports/journal' },
  { name: 'По водителям', href: '/reports/by-driver' },
  { name: 'По транспорту', href: '/reports/by-vehicle' },
  { name: 'Топливо', href: '/reports/fuel' },
  { name: 'Разрезы Роҳхат', href: '/reports/sections' },
  { name: 'Справки', href: '/reports/malumotnoma' },
  { name: 'Журналы контроля', href: '/reports/journals' },
];

test.describe('Accountant: reports access + waybill-creation gating', () => {
  test('logs in and lands on /reports/summary', async ({ page }) => {
    await loginAs(page, 'accountant');
    await expect(page).toHaveURL(/\/reports\/summary$/);
  });

  test('every visible report tab loads without a 403', async ({ page }) => {
    await loginAs(page, 'accountant');

    // The Mintrans-only "Сводный (Минтранс)" tab must not even be offered to an accountant.
    await expect(page.getByRole('link', { name: 'Сводный (Минтранс)' })).toHaveCount(0);

    for (const tab of EXPECTED_TABS) {
      await page.getByRole('link', { name: tab.name, exact: true }).click();
      await expect(page).toHaveURL(new RegExp(tab.href.replace(/\//g, '\\/') + '$'));
      // Regression guard: ReportController used to 403 accountants at the class level.
      const errorBanner = page.locator('.error');
      await expect(errorBanner).toHaveCount(0, { timeout: 8_000 });
    }
  });

  test('cannot create waybills: no nav entry, and /waybills/new explains why instead of cascading 403s', async ({ page }) => {
    await loginAs(page, 'accountant');

    // No "Создать путевой лист" entry anywhere in the sidebar for this role.
    await expect(page.getByRole('link', { name: 'Создать путевой лист' })).toHaveCount(0);

    // Direct navigation to the creation wizard shows a clear explanation, not a raw 403.
    await page.goto('/waybills/new');
    await expect(page.getByText('Выписка путевого листа — задача диспетчера.')).toBeVisible();
    await expect(page.locator('.error')).toHaveCount(0);
    // And definitely not the raw wizard form (org/vehicle/type step) that would then
    // fail every downstream request with 403.
    await expect(page.getByRole('button', { name: /Легковой/ })).toHaveCount(0);
  });
});
