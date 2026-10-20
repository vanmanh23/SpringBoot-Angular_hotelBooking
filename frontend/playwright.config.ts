import { defineConfig, devices } from '@playwright/test';

/**
 * Browser E2E against the running Compose stack (frontend nginx on :4200 → gateway → services).
 * Start it first: `docker compose up -d --build --wait`. Admin credentials come from the environment
 * (E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD = the ADMIN_* values of .env); nothing is hard-coded.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env['E2E_BASE_URL'] ?? 'http://localhost:4200',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile', use: { ...devices['Pixel 7'] }, grep: /@mobile/ },
    // Cross-browser (M7): run with --project=firefox / --project=webkit, or E2E_ALL_BROWSERS=1 for everything.
    ...(process.env['E2E_ALL_BROWSERS']
      ? [
          { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
          { name: 'webkit', use: { ...devices['Desktop Safari'] } },
        ]
      : []),
  ],
});
