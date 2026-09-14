import { test, expect } from '@playwright/test';
import { ACCOUNTS, login, logout } from './fixtures';

/**
 * Golden path 5: a lightweight smoke pass for every demo account. Not deep — just
 * "the cabinet loads for this role, the sidebar renders without an obviously broken
 * shell, and logout works." Deep per-role behavior is covered by the dedicated specs
 * (dispatcher.spec.ts, accountant.spec.ts, inspector.spec.ts, org-scope.spec.ts).
 */

test.describe('Smoke: every demo account can log in, land on its cabinet, and log out', () => {
  for (const acct of ACCOUNTS) {
    test(`${acct.username} (${acct.role})`, async ({ page }) => {
      const consoleErrors: string[] = [];
      page.on('pageerror', e => consoleErrors.push(e.message));

      await login(page, acct.username, acct.password);
      await expect(page).toHaveURL(new RegExp(acct.home.replace(/\//g, '\\/') + '$'));

      // The app shell (sidebar) rendered, and there's no client-side crash overlay.
      await expect(page.locator('.sidebar')).toBeVisible();
      await expect(page.getByText('Application error', { exact: false })).toHaveCount(0);

      // Sidebar links resolve to real paths, not "#" or empty hrefs (a broken-link smoke check).
      const hrefs = await page.locator('.side-nav a[href]').evaluateAll(
        els => els.map(el => (el as HTMLAnchorElement).getAttribute('href')),
      );
      for (const href of hrefs) {
        expect(href, `sidebar link has a real href`).toBeTruthy();
        expect(href).not.toBe('#');
      }

      expect(consoleErrors, `no uncaught client-side errors for ${acct.username}`).toEqual([]);

      await logout(page);
      await expect(page).toHaveURL(/\/login$/);
    });
  }
});
