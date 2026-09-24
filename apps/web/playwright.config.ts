import { defineConfig, devices } from '@playwright/test';

/**
 * E2E regression suite against the LIVE dev/pilot stack (Docker: web :3000,
 * master-data :8081, waybill :8082). This config does NOT start or manage any
 * servers — the stack is assumed to already be running.
 *
 * Through the HTTPS reverse proxy (CSP and security headers apply there):
 *   E2E_BASE_URL=https://localhost npx playwright test ...
 * The pilot proxy may use a self-signed certificate, hence ignoreHTTPSErrors.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: 1,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'e2e-report' }]],
  timeout: 45_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://localhost:3000',
    ignoreHTTPSErrors: true,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
