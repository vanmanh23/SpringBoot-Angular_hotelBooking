import { expect, test } from '@playwright/test';
import { adminCredentials, Api, axeViolations, expectNoHorizontalScroll, signIn } from './support';

/** Automated accessibility (axe, WCAG 2.1 AA), responsive layout and error states on the real stack. */
test.describe('quality', () => {
  let hotelId: string;

  test.beforeAll(async ({ baseURL }) => {
    const admin = await Api.login(baseURL!, adminCredentials().email, adminCredentials().password);
    hotelId = (await admin.activeHotel()).id;
  });

  test.afterAll(async ({ baseURL }) => {
    // keep the public catalogue clean: test hotels never stay visible after a run
    const admin = await Api.login(baseURL!, adminCredentials().email, adminCredentials().password);
    await admin.send('PATCH', `/api/v1/hotels/${hotelId}/status`, { status: 'INACTIVE' });
  });

  const publicPages = () => ['/', '/hotels?city=E2E', `/hotels/${hotelId}`, '/login', '/register', '/does-not-exist'];

  test('public pages have no WCAG A/AA violations and no horizontal scroll @mobile', async ({ page }) => {
    for (const path of publicPages()) {
      await page.goto(path);
      await page.waitForLoadState('networkidle');
      expect(await axeViolations(page), path).toEqual([]);
      await expectNoHorizontalScroll(page);
    }
  });

  test('staff pages have no WCAG A/AA violations and no horizontal scroll @mobile', async ({ page }) => {
    await signIn(page, adminCredentials().email, adminCredentials().password);
    for (const path of ['/my-bookings', '/notifications', '/profile', '/manage', `/manage/hotels/${hotelId}`,
      '/admin?section=users', '/admin?section=payments', '/admin?section=reviews']) {
      await page.goto(path);
      await page.waitForLoadState('networkidle');
      expect(await axeViolations(page), path).toEqual([]);
      if (!path.startsWith('/admin') && !path.startsWith('/manage/')) await expectNoHorizontalScroll(page); // wide tables scroll inside .table-wrap
    }
  });

  test('keyboard only: search and reach results', async ({ page }) => {
    await page.goto('/');
    await page.keyboard.press('Tab'); // brand link
    let reachedDestination = false;
    for (let i = 0; i < 10 && !reachedDestination; i++) {
      await page.keyboard.press('Tab');
      reachedDestination = await page.evaluate(() => document.activeElement?.id === 'sf-city');
    }
    expect(reachedDestination).toBe(true);
    await page.keyboard.type('E2E');
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/\/hotels\?.*city=E2E/);
  });

  test('a backend outage shows a retry state instead of a blank page', async ({ page }) => {
    await page.route('**/api/v1/hotels?**', (route) => route.fulfill({ status: 503, contentType: 'application/json',
      body: JSON.stringify({ status: 503, code: 'SERVICE_UNAVAILABLE', message: 'Service unavailable', fieldErrors: [] }) }));
    await page.goto('/hotels?city=Hue');
    await expect(page.getByRole('alert')).toBeVisible();
    await page.unroute('**/api/v1/hotels?**');
    await page.getByRole('button', { name: 'Try again' }).click();
    await expect(page.getByRole('alert')).toBeHidden();
  });

  test('unknown routes and hidden hotels show friendly pages', async ({ page }) => {
    await page.goto('/nope/nothing');
    await expect(page.getByRole('heading', { name: /not found/i })).toBeVisible();
    await page.goto('/hotels/00000000-0000-0000-0000-000000000000');
    await expect(page.getByRole('heading', { name: 'Hotel not found' })).toBeVisible();
  });
});
