import { test, expect } from '@playwright/test';
import { loginAs } from './fixtures';

// Сверка 25.09, E6: страница регионов с «Рамз», поиск зарубежных городов по всем странам,
// номер направления обязателен.
test('справочники: регионы с рамзом, поиск зарубежного города, обязательный номер направления', async ({ page }) => {
  await loginAs(page, 'admin-automation');

  await page.goto('/dictionaries/regions');
  await expect(page.locator('main table tbody tr')).toHaveCount(7, { timeout: 15_000 });
  await expect(page.locator('main table tbody')).toContainText('3501');
  await expect(page.locator('main table tbody')).toContainText('3590');

  await page.goto('/dictionaries/external-cities');
  await page.getByTestId('extcity-q').fill('Моск');
  await expect(page.getByTestId('extcity-row').first()).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('extcity-row').filter({ hasText: 'Москва' }).first()).toContainText('Россия');

  await page.goto('/dictionaries/directions');
  await page.getByRole('button', { name: 'Добавить' }).first().click();
  const number = page.locator('form input[type="number"]').first();
  await expect(number).toHaveAttribute('required', '');
  await expect(page.locator('.error')).toHaveCount(0);
});
