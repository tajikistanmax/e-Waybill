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

// Сверка 25.09, D13 — колонки legacy типов 2/3/5 (наименование маршрута, рамз марки, ТС, план рейсов, время по
// заказу) и титул печатного отчёта.
test('бухгалтер: колонки маршрутов/марок/зарплаты и титул печати', async ({ page }) => {
  await loginAs(page, 'accountant');
  await page.goto('/reports/passenger');
  const type = page.getByTestId('rep-type');
  await expect(type.locator('option[value="BY_ROUTE"]')).toHaveCount(1, { timeout: 15_000 });

  await type.selectOption('BY_ROUTE');
  await expect(page.locator('main th', { hasText: '№ маршрута' })).toBeVisible({ timeout: 15_000 });
  await expect(page.locator('main th', { hasText: 'Вид маршрута' })).toBeVisible();
  await expect(page.locator('main th', { hasText: /^ТС$/ })).toBeVisible();
  await expect(page.locator('main th', { hasText: 'Рейсы план' })).toBeVisible();

  await type.selectOption('BY_BRAND');
  await expect(page.locator('main th', { hasText: 'Рамз марки' })).toBeVisible({ timeout: 15_000 });

  await type.selectOption('DRIVER_SALARY');
  await expect(page.locator('main th', { hasText: 'По заказу, ч' })).toBeVisible({ timeout: 15_000 });
  await expect(page.locator('main th', { hasText: 'Таб. №' })).toBeVisible();

  // Титул печати есть в разметке (на экране скрыт), в нём период.
  await expect(page.getByTestId('rep-print-title')).toBeHidden();
  await expect(page.getByTestId('rep-print-title')).toContainText('Музди меҳнати ронандагони');
  await expect(page.getByTestId('rep-print')).toBeVisible();
  await expect(page.locator('.error')).toHaveCount(0);
});
