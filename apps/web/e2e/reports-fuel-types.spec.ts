import { test, expect } from '@playwright/test';
import { loginAs } from './fixtures';

// Сверка 25.09, D6 — топливо по видам Б/С/Г: колонки есть у топливных отчётов и нет у остальных.
test('бухгалтер: топливный отчёт по ТС — колонки Б/С/Г', async ({ page }) => {
  await loginAs(page, 'accountant');
  await page.goto('/reports/passenger');
  const typeSelect = page.locator('main select').filter({ has: page.locator('option', { hasText: 'Топливо по ТС' }) });
  await typeSelect.selectOption({ label: 'Топливо по ТС' });
  await expect(page.locator('main th', { hasText: 'Норма Б, л' })).toBeVisible({ timeout: 15_000 });
  await expect(page.locator('main th', { hasText: 'Откл. С, л' })).toBeVisible();
  const other = await typeSelect.locator('option').filter({ hasNotText: 'Топливо' }).first().textContent();
  await typeSelect.selectOption({ label: String(other).trim() });
  await expect(page.locator('main th', { hasText: 'Норма Б, л' })).toHaveCount(0);
  await expect(page.locator('.error')).toHaveCount(0);
});
