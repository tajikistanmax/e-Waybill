import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { loginAs } from './fixtures';

// Сверка 25.09, F6 — отметка «Қарздор» у водителя перевозчиком (диспетчер), отметка снимается обратно.
const fx = JSON.parse(readFileSync(resolve(process.cwd(), '../../scripts/e2e-fixture.json'), 'utf8'));

test('диспетчер: «Қарздор» у водителя — отметить и снять', async ({ page }) => {
  const driver = fx.forms.find((f: { type: string }) => f.type === 'WB_TAXI').driverRma as string;
  await loginAs(page, 'dispatcher');
  await page.goto('/fleet/drivers');
  await page.getByPlaceholder(/ИНН|Ф\.И\.О|водител/i).first().fill(driver);
  const row = page.locator('tbody tr', { hasText: driver });
  await expect(row).toHaveCount(1);

  page.once('dialog', d => d.accept('E2E долг'));
  await row.getByTestId('debtor-toggle').click();
  await expect(row.getByTestId('debtor-badge')).toBeVisible();

  page.once('dialog', d => d.accept());
  await row.getByTestId('debtor-toggle').click();
  await expect(row.getByTestId('debtor-badge')).toHaveCount(0);
  await expect(page.locator('.error')).toHaveCount(0);
});
