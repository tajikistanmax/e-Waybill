import { test, expect } from '@playwright/test';
import { loginAs } from './fixtures';

/**
 * Реестр организаций (сверка 25.09, F10): аналитик Минтранса видит перевозчиков — список, поиск,
 * фильтр по виду и статусу, число ТС/водителей. Только просмотр (кнопок блокировки нет).
 */
test('аналитик: реестр организаций — список, поиск, фильтры', async ({ page }) => {
  await loginAs(page, 'analyst-automation');
  await page.goto('/registry/organizations');
  const table = page.getByTestId('orgs-table');
  await expect(table.locator('tbody tr').first()).toBeVisible({ timeout: 15_000 });
  const total = await table.locator('tbody tr').count();
  expect(total).toBeGreaterThan(0);

  await page.getByLabel('Поиск', { exact: true }).fill('КВД');
  await expect(table.locator('tbody tr').first()).toContainText('КВД');

  await page.getByLabel('Поиск', { exact: true }).fill('');
  await page.getByLabel('Вид', { exact: true }).selectOption('1');
  await expect(table.locator('tbody tr').first()).toContainText('Общего пользования');

  await expect(page.getByRole('button', { name: /Заблокировать/ })).toHaveCount(0);
  await expect(page.locator('.error')).toHaveCount(0);
});
