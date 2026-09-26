import { test, expect } from '@playwright/test';
import { loginAs } from './fixtures';

// Сверка 25.09, F9 — администратор перевозчика загружает подпись сотрудника (печатается на бланках) и
// видит документы/печать своей организации. Загруженная подпись одобряется и удаляется в конце теста.
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64');

test('админ компании: подпись сотрудника и документы организации', async ({ page }) => {
  await loginAs(page, 'company');
  await page.goto('/fleet/employees');
  await page.getByTestId('subject-docs').first().click();
  const modal = page.locator('.card', { hasText: 'Документы' }).last();
  await modal.locator('select').first().selectOption('SIGNATURE');
  await modal.locator('input[type=file]').setInputFiles({ name: 'sign.png', mimeType: 'image/png', buffer: PNG });
  await modal.getByRole('button', { name: 'Прикрепить' }).click();
  const row = modal.locator('tbody tr', { hasText: 'sign.png' });
  await expect(row).toHaveCount(1);
  await row.getByRole('button', { name: /Одобрить/ }).click();
  await expect(row).toContainText(/Одобрен/);
  page.once('dialog', d => d.accept());
  await row.getByRole('button', { name: '×' }).click();
  await expect(modal.locator('tbody tr', { hasText: 'sign.png' })).toHaveCount(0);

  await page.goto('/fleet/organization');
  await expect(page.getByRole('heading', { name: 'Документы и печать организации' })).toBeVisible();
  await expect(page.locator('.error')).toHaveCount(0);
});
