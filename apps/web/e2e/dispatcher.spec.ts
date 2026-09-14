import { test, expect } from '@playwright/test';
import { login } from './fixtures';

/**
 * Golden path 1 (dispatcher): log in, land on the dispatcher cabinet, create a new
 * waybill through the real UI wizard, confirm it shows up in /waybills, and verify
 * the waybill card's print action targets the real server-rendered print document
 * (/waybills/[id]/print) rather than calling window.print() on the app chrome —
 * a regression test for a real bug that shipped and was fixed.
 *
 * Fixtures used (verified against the live master-data-service for org 025680800,
 * the dispatcher demo user's organization):
 *  - vehicle 5500TJ33 (Toyota Corolla, легковой) — eligible for WB_CAR
 *  - driver RMA 777100208 (license categories B, C, D) — passes the "B" requirement
 *    for a car waybill; the demo DRIVER account (RMA 555555555, category D only) does
 *    NOT and was rejected by preflight during investigation — a good reminder that
 *    preflight eligibility is real and must be respected by any test data picked here.
 */

test.describe('Dispatcher: create waybill + print link regression', () => {
  test('logs in and lands on the dispatcher cabinet', async ({ page }) => {
    await login(page, 'dispatcher', 'dispatcher');
    await expect(page).toHaveURL(/\/dispatcher$/);
    await expect(page.getByRole('heading', { name: 'Кабинет диспетчера' })).toBeVisible();
  });

  test('creates a waybill via the UI wizard and it appears in /waybills', async ({ page }) => {
    await login(page, 'dispatcher', 'dispatcher');

    await page.getByRole('link', { name: 'Создать путевой лист' }).click();
    await expect(page).toHaveURL(/\/waybills\/new$/);

    // Step 1 — type. Default selection is WB_BUS; switch to "Легковой" (WB_CAR).
    await page.getByRole('button', { name: /Легковой/ }).click();
    await page.getByRole('button', { name: 'Далее' }).click();

    // Step 2 — organization is auto-selected (dispatcher belongs to exactly one org).
    // Pick vehicle and driver through the server-search autocomplete.
    await page.getByPlaceholder(/Введите госномер ТС/).fill('5500TJ33');
    await page.getByText('5500TJ33', { exact: true }).first().click();

    await page.getByPlaceholder(/Введите ИНН или Ф\.И\.О/).fill('777100208');
    await page.getByText(/777100208/).first().click();

    await expect(page.getByRole('button', { name: 'Далее' })).toBeEnabled();
    await page.getByRole('button', { name: 'Далее' }).click();

    // Step 3 — route/type-specific params. WB_CAR + default service kind TAXI needs no
    // extra required fields. The "Далее" button is gated on the real server-authoritative
    // preflight eligibility check (see lib/api.ts wb.preflight) — waiting for it to become
    // enabled means we've actually waited for that check to resolve "eligible", exactly
    // like a real dispatcher would before advancing.
    const next3 = page.getByRole('button', { name: 'Далее' });
    await expect(next3).toBeEnabled({ timeout: 15_000 });
    await next3.click();

    // Step 4 — review and submit.
    await expect(page).toHaveURL(/\/waybills\/new$/);
    const createBtn = page.getByRole('button', { name: 'Создать путевой лист' });
    await expect(createBtn).toBeVisible();
    await createBtn.click();

    // Submit navigates to the fresh waybill's own card.
    await page.waitForURL(/\/waybills\/[0-9a-f-]{36}$/, { timeout: 20_000 });
    const waybillId = page.url().split('/').pop()!;

    // Now confirm it appears in the registry list.
    await page.goto('/waybills');
    await page.getByPlaceholder('№ ПЛ, водитель, транспорт или компания').fill('5500TJ33');
    await expect(page.locator(`a[href="/waybills/${waybillId}"]`)).toBeVisible({ timeout: 10_000 });
  });

  test('the print action on a waybill card targets the real print document, not window.print()', async ({ page }) => {
    await login(page, 'dispatcher', 'dispatcher');
    await page.goto('/waybills');

    // Filter to a status that is guaranteed to carry an assigned number (DRAFT waybills
    // have number=null and show no print action at all — that's by design, not the bug
    // under test). COMPLETED waybills in this org's history all have numbers.
    await page.locator('select').nth(1).selectOption('COMPLETED');
    const firstOpen = page.getByRole('link', { name: 'Открыть' }).first();
    await expect(firstOpen).toBeVisible({ timeout: 10_000 });
    await firstOpen.click();

    await page.waitForURL(/\/waybills\/[0-9a-f-]{36}$/);
    const id = page.url().split('/').pop()!;

    const printLink = page.getByRole('link', { name: /Печатная форма/ });
    await expect(printLink).toBeVisible();
    // Core regression assertion: this must be a real navigable link to the dedicated
    // server-rendered print route, not a button wired to window.print() on this page.
    await expect(printLink).toHaveAttribute('href', `/waybills/${id}/print`);
    expect(await printLink.evaluate(el => el.tagName)).toBe('A');

    await printLink.click();
    await page.waitForURL(`**/waybills/${id}/print`);
    // The print route renders a distinct printable document (.sheet) — proof this is
    // the real server-rendered blank, not just window.print() called on the app chrome.
    await expect(page.locator('.sheet')).toBeVisible({ timeout: 10_000 });
  });
});
