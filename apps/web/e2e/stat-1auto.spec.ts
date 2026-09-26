import { test, expect } from '@playwright/test';
import { loginAs } from './fixtures';

// Сверка 25.09, D4 — статформа «1-авто»: строки 01–44 в пассажирских отчётах, Excel.
test('бухгалтер: статформа 1-авто — строки 01–44', async ({ page }) => {
  await loginAs(page, 'accountant');
  await page.goto('/reports/passenger');
  await page.getByTestId('rep-type').selectOption('STAT_1AUTO');
  const form = page.getByTestId('stat-1auto');
  await expect(form).toBeVisible({ timeout: 30_000 });
  await expect(form.locator('tbody tr').filter({ hasText: 'Шумораи мошинҳо' })).toHaveCount(1, { timeout: 30_000 });
  for (const code of ['01', '03', '07', '12', '17', '22', '27', '40', '44']) {
    await expect(form.locator('td.number', { hasText: new RegExp(`^${code}$`) })).toHaveCount(1);
  }
  // Бланк/ТС/водитель к статформе не относятся — скрыты.
  await expect(page.getByTestId('rep-bill')).toHaveCount(0);
  await expect(page.locator('.error')).toHaveCount(0);
});
