import { test, expect } from '@playwright/test';
import { login } from './fixtures';

/**
 * Golden path 4 (inspector): the "Neru" active-waybill-by-plate lookup is present and
 * functional-looking (no crash on a miss), and carrier-economics report tabs (fuel,
 * salary, by-driver/by-vehicle breakdowns) are hidden from the inspector per
 * canSeeCarrierEconomics() in lib/roles.ts, while the summary + control-journal reports
 * stay visible.
 */

test.describe('Inspector: Neru plate lookup + carrier-economics gating', () => {
  test('logs in and lands on the inspector cabinet', async ({ page }) => {
    await login(page, 'inspector', 'inspector');
    await expect(page).toHaveURL(/\/inspector$/);
    await expect(page.getByRole('heading', { name: 'Действующий лист по госномеру' })).toBeVisible();
  });

  test('Neru plate search runs without error, on both a miss and a real plate', async ({ page }) => {
    await login(page, 'inspector', 'inspector');

    // Two "Проверить" buttons exist on this page (archive-search console + Neru
    // lookup) — scope to the Neru card specifically by its heading.
    const neruCard = page.locator('.card', { has: page.getByRole('heading', { name: 'Действующий лист по госномеру' }) });
    const plateInput = neruCard.getByPlaceholder(/Госномер/);
    const checkBtn = neruCard.getByRole('button', { name: 'Проверить' });

    // A plate that should not exist -> a clean "not found" state, not a crash.
    await plateInput.fill('ZZ0000ZZ99');
    await checkBtn.click();
    await expect(page.getByText('Действующий путевой лист не найден')).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('.error')).toHaveCount(0);

    // A real plate from this org's fleet — should not error either way (found or not,
    // depending on whether it currently has an ACTIVE/ISSUED/RETURNED waybill).
    await plateInput.fill('5500TJ33');
    await checkBtn.click();
    await page.waitForTimeout(500); // let the async check settle
    await expect(page.locator('.error')).toHaveCount(0);
  });

  test('carrier-economics report tabs are hidden; summary and control journals remain', async ({ page }) => {
    await login(page, 'inspector', 'inspector');
    await page.goto('/reports/summary');

    await expect(page.getByRole('link', { name: 'Сводка', exact: true })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Журналы контроля', exact: true })).toBeVisible();

    for (const hidden of ['Журнал диспетчера', 'По водителям', 'По транспорту', 'Топливо', 'Разрезы Роҳхат', 'Справки', 'Сводный (Минтранс)']) {
      await expect(page.getByRole('link', { name: hidden, exact: true })).toHaveCount(0);
    }
  });
});
