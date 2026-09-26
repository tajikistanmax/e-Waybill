import { test, expect } from '@playwright/test';
import { loginAs } from './fixtures';

// Сверка 25.09, D5 — построчные отчёты legacy: «Сведения о рейсах» (тип 6) и «Реестр» (тип 7) по каждому листу
// с одометром, «Топливо — остатки по ТС» (тип 9) по ТС с реквизитами последнего листа.
test('бухгалтер: построчные отчёты — сведения о рейсах, реестр, остатки топлива', async ({ page }) => {
  await loginAs(page, 'accountant');
  await page.goto('/reports/passenger');
  const type = page.getByTestId('rep-type');
  await expect(type.locator('option[value="TRIP_INFO"]')).toHaveCount(1, { timeout: 15_000 });

  await type.selectOption('TRIP_INFO');
  await expect(page.locator('main th', { hasText: 'Одометр выезд' })).toBeVisible({ timeout: 15_000 });
  await expect(page.locator('main th', { hasText: 'Разница, км' })).toBeVisible();

  await type.selectOption('REGISTRY_JOURNAL');
  await expect(page.locator('main th', { hasText: 'Таб. №' })).toBeVisible({ timeout: 15_000 });
  await expect(page.locator('main th', { hasText: /^Возврат$/ })).toBeVisible();

  await type.selectOption('FUEL_GENERAL');
  await expect(page.locator('main th', { hasText: 'Остаток при возврате, л' })).toBeVisible({ timeout: 15_000 });
  await expect(page.locator('.error')).toHaveCount(0);
});
