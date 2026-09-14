import { test, expect } from '@playwright/test';
import { login } from './fixtures';

/**
 * Golden path 3 (company admin vs branch admin scoping).
 *
 * IMPORTANT finding from investigating the live data before writing this test: the task
 * brief assumed branch/branch (org RMA 100002091, "Филиал «Юг» (демо)") is a branch of
 * company/company's organization (025680800, "ООО Автобусы Душанбе"). That is NOT what
 * the seeded data says — org 100002091's parentRma is actually 100002000 ("ГУП «Троллейбус
 * Худжанд»"), a company with no demo COMPANY_ADMIN login at all. Confirmed directly against
 * master-data-service: GET /api/v1/organizations as `company` returns only 025680800 (no
 * branches); the same call as `branch` returns only 100002091. So in THIS environment,
 * `company` has zero branches and the header branch-switcher never renders for it (its
 * visibility is gated on `myOrganizations().length > 1`) — that is correct behavior given
 * the data, not a bug, but it means the "company sees own + branch in switcher" half of the
 * originally-described scenario cannot be exercised here without fixing the seed data.
 *
 * What IS still fully testable, and is the real security-relevant intent behind this golden
 * path, is tenant isolation: neither admin should see the other's organization data, and the
 * scope switcher must never appear for a BRANCH_ADMIN regardless of data (only COMPANY_ADMIN/
 * SYSTEM_ADMIN can switch — see lib/orgscope.tsx `canSwitch`). That is what this spec locks in.
 */

test.describe('Company admin vs branch admin: scope isolation', () => {
  test('company admin sees only its own organization, never the unrelated branch org', async ({ page }) => {
    await login(page, 'company', 'company');
    await expect(page).toHaveURL(/\/dashboard$/);

    await page.goto('/company');
    await expect(page.getByText('025680800')).toBeVisible();
    await expect(page.getByText('100002091')).toHaveCount(0);

    // Branch switcher: gated on having >1 organization in scope. With no branches under
    // 025680800 in this dataset, it must not render (see file-level note above).
    await expect(page.locator('.branch-switch')).toHaveCount(0);

    // /company/access org picker (labelled "Организация / филиал") must likewise offer
    // only the organizations md.organizations() scopes to this user — one, here.
    await page.goto('/company/access');
    const orgSelect = page.locator('label:has-text("Организация / филиал") + select');
    await expect(orgSelect).toBeVisible();
    await expect(orgSelect.locator('option')).toHaveCount(1);
  });

  test('branch admin sees only its own branch, never the parent/other orgs, and gets no scope switcher', async ({ page }) => {
    await login(page, 'branch', 'branch');
    await expect(page).toHaveURL(/\/dashboard$/);

    await page.goto('/company');
    await expect(page.getByText('100002091')).toBeVisible();
    await expect(page.getByText('025680800')).toHaveCount(0);
    await expect(page.getByText('100002000')).toHaveCount(0);

    // BRANCH_ADMIN is structurally excluded from the switcher (lib/orgscope.tsx `canSwitch`
    // only allows COMPANY_ADMIN/SYSTEM_ADMIN) — this must hold regardless of org hierarchy data.
    await expect(page.locator('.branch-switch')).toHaveCount(0);

    await page.goto('/company/access');
    const orgSelect = page.locator('label:has-text("Организация / филиал") + select');
    await expect(orgSelect).toBeVisible();
    await expect(orgSelect.locator('option')).toHaveCount(1);
  });
});
