import { test, expect } from '@playwright/test';
import { loginAs } from './fixtures';

// Сверка 25.09, F5 — активность ТС за период в реестре надзора (аналитик, все организации).
test('аналитик: активность ТС за 30 дней по всем организациям', async ({ page }) => {
  await loginAs(page, 'analyst-automation');
  await page.goto('/registry/vehicles');
  const panel = page.getByTestId('activity-panel');
  const from = new Date(Date.now() - 30 * 86400_000).toISOString().slice(0, 10);
  await panel.getByLabel('Активность с').fill(from);
  await panel.getByRole('button', { name: 'Показать' }).click();
  const rows = page.getByTestId('activity-table').locator('tbody tr');
  await expect(rows.first()).toBeVisible();
  await expect(rows.first().locator('td').nth(1)).not.toHaveText('—');   // организация определена
  await expect(page.locator('.error')).toHaveCount(0);
});
