import { test, expect } from '@playwright/test';
import { loginAs } from './fixtures';

/**
 * Golden path 3 (company admin vs branch admin scoping).
 *
 * Demo hierarchy (seed-demo-data / seed-demo-showcase): company/company is COMPANY_ADMIN of
 * 025680800 «Автобуси Душанбе»; its branches are 100002091 «Юг» (branch/branch is its
 * BRANCH_ADMIN) and 100002092 «Север». 040000830 «Троллейбус» is an unrelated carrier.
 * (Until 23.09 the seed hung «Юг» under a different parent and this spec asserted the company
 * could NOT see it; the data now matches the intended scenario, so the spec does too.)
 *
 * Locked in: the company admin sees its own organization and its branches — never another
 * carrier — and gets the scope switcher; the branch admin sees only its branch (not the parent,
 * not the sibling) and never gets a switcher (lib/orgscope.tsx `canSwitch`).
 */

test.describe('Company admin vs branch admin: scope isolation', () => {
  test('company admin sees its organization and branches, never another carrier', async ({ page }) => {
    await loginAs(page, 'company');
    await expect(page).toHaveURL(/\/dashboard$/);

    await page.goto('/company');
    await expect(page.getByText('025680800').first()).toBeVisible();
    await expect(page.getByText('040000830')).toHaveCount(0);

    // More than one organization in scope → the header scope switcher is offered.
    await expect(page.locator('.branch-switch')).toHaveCount(1);

    // «Доступы»: the grant form offers the company and its two branches — nothing else.
    await page.goto('/company/access');
    const orgSelect = page.locator('#acc-org');
    await expect(orgSelect).toBeVisible();
    await expect(orgSelect.locator('option')).toHaveCount(3);
    await expect(orgSelect.locator('option[value="040000830"]')).toHaveCount(0);
  });

  test('branch admin sees only its own branch, never the parent/other orgs, and gets no scope switcher', async ({ page }) => {
    await loginAs(page, 'branch');
    await expect(page).toHaveURL(/\/dashboard$/);

    await page.goto('/company');
    await expect(page.getByText('100002091').first()).toBeVisible();
    await expect(page.getByText('025680800')).toHaveCount(0);
    await expect(page.getByText('100002092')).toHaveCount(0);

    // BRANCH_ADMIN is structurally excluded from the switcher (lib/orgscope.tsx `canSwitch`
    // only allows COMPANY_ADMIN/SYSTEM_ADMIN) — this must hold regardless of org hierarchy data.
    await expect(page.locator('.branch-switch')).toHaveCount(0);

    await page.goto('/company/access');
    const orgSelect = page.locator('#acc-org');
    await expect(orgSelect).toBeVisible();
    await expect(orgSelect.locator('option')).toHaveCount(1);
  });
});
