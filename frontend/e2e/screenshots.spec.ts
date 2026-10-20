import { test } from '@playwright/test';
import { adminCredentials, signIn } from './support';

/**
 * Visual snapshots of the main pages for design review (not an assertion suite).
 * Opt-in: SCREENSHOTS_DIR=<folder> npx playwright test e2e/screenshots.spec.ts
 */
const dir = process.env['SCREENSHOTS_DIR'];

test.describe('screenshots @mobile', () => {
  test.skip(!dir, 'set SCREENSHOTS_DIR to capture');

  test('public pages', async ({ page }, info) => {
    const shot = async (name: string) => {
      await page.waitForLoadState('networkidle');
      await page.screenshot({ path: `${dir}/${info.project.name}-${name}.png`, fullPage: true });
    };
    await page.goto('/');
    await shot('1-home');
    await page.goto('/hotels?city=Da%20Nang&guests=2');
    await shot('2-results');
    await page.getByRole('link', { name: 'Sơn Trà Beach Resort' }).first().click();
    await page.getByRole('heading', { level: 1, name: 'Sơn Trà Beach Resort' }).waitFor();
    await page.getByText(/Guest reviews/).waitFor();
    await shot('3-hotel-detail');
    await page.goto('/login');
    await shot('4-login');
  });

  test('signed-in pages', async ({ page }, info) => {
    await signIn(page, adminCredentials().email, adminCredentials().password);
    for (const [name, path] of [['5-my-bookings', '/my-bookings'], ['6-notifications', '/notifications'], ['7-admin', '/admin?section=users']]) {
      await page.goto(path);
      await page.waitForLoadState('networkidle');
      await page.screenshot({ path: `${dir}/${info.project.name}-${name}.png`, fullPage: true });
    }
  });
});
