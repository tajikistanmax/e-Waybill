import { test, expect } from '@playwright/test';
import { loginAs, logout } from './fixtures';

// Сверка 25.09, E5/B7 — маълумотнома: сквозной номер, поиск по номеру, правка без смены Ф.И.О.,
// удаления нет — администратор аннулирует с причиной.
test('справка: выдача с номером, поиск, правка, аннулирование администратором', async ({ page }) => {
  const fio = `E2E Маълумотнома ${Date.now()}`;
  await loginAs(page, 'accountant');
  await page.goto('/reports/malumotnoma');
  const form = page.getByTestId('rm-form');
  await form.locator('input').first().fill(fio);
  const line = page.getByTestId('rm-line-0');
  await expect.poll(() => line.locator('option').count(), { timeout: 15_000 }).toBeGreaterThan(1);
  // Первый маршрут с ненулевым тарифом легкового.
  const value = await line.locator('option').evaluateAll(opts =>
    (opts as HTMLOptionElement[]).find(o => o.value && !/\(0 с\.\)/.test(o.text))?.value ?? '');
  expect(value).not.toBe('');
  await line.selectOption(value);
  await page.getByTestId('rm-submit').click();
  const ok = page.getByTestId('rm-ok');
  await expect(ok).toContainText('№', { timeout: 15_000 });
  const number = (await ok.textContent())!.match(/№\s*(\d+)/)![1];

  // Поиск по номеру — одна строка, номер в первой колонке; кнопки «Удалить» нет.
  await page.getByTestId('rm-q').fill(number);
  const row = page.getByTestId('rm-row').filter({ hasText: fio });
  await expect(row).toHaveCount(1, { timeout: 10_000 });
  await expect(row.locator('td').first()).toHaveText(number);
  await expect(row.getByRole('button', { name: '×' })).toHaveCount(0);
  await expect(row.getByRole('button', { name: 'Аннулировать' })).toHaveCount(0);

  // Правка: Ф.И.О. заблокировано, льгота → цена пересчитана, номер тот же.
  await row.getByRole('button', { name: 'Изменить' }).click();
  await expect(form.locator('h2')).toContainText(number);
  await expect(form.locator('input').first()).toBeDisabled();
  await form.getByText('Льготная (−50%)').click();
  await page.getByTestId('rm-submit').click();
  await expect(ok).toContainText(`Справка изменена: № ${number}`, { timeout: 15_000 });

  // Аннулирование — администратор компании, с причиной.
  await logout(page);
  await loginAs(page, 'company');
  await page.goto('/reports/malumotnoma');
  await page.getByTestId('rm-q').fill(number);
  const arow = page.getByTestId('rm-row').filter({ hasText: fio });
  await expect(arow).toHaveCount(1, { timeout: 10_000 });
  page.once('dialog', d => d.accept('e2e: ошибка кассира'));
  await arow.getByRole('button', { name: 'Аннулировать' }).click();
  await expect(arow).toContainText('аннулирована', { timeout: 10_000 });
  await expect(arow.getByRole('button', { name: 'Изменить' })).toHaveCount(0);
  await expect(page.locator('.error')).toHaveCount(0);
});
