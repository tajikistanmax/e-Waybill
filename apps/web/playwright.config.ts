import { defineConfig, devices } from '@playwright/test';

/**
 * E2E regression suite against the LIVE dev/pilot stack (Docker: web :3000,
 * master-data :8081, waybill :8082, Keycloak :8180). This config does NOT start
 * or manage any servers — the stack is assumed to already be running.
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
