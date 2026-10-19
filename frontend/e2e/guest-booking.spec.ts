import { expect, test } from '@playwright/test';
import { adminCredentials, Api, isoDate, PASSWORD, uid } from './support';

/** Core customer journey in a real browser: search → detail → login → checkout → sandbox payment → confirmation. */
test.describe('guest to confirmed booking', () => {
  test('search, register, reserve, pay and see the confirmation and notifications @mobile', async ({ page, baseURL }) => {
    const admin = await Api.login(baseURL!, adminCredentials().email, adminCredentials().password);
    const hotel = await admin.activeHotel();
    const checkIn = isoDate(30);
    const checkOut = isoDate(32);

    // search as a guest
    await page.goto('/');
    await page.getByLabel('Destination').fill(hotel.city);
    await page.getByLabel('Check-in').fill(checkIn);
    await page.getByLabel('Check-out').fill(checkOut);
    await page.getByRole('button', { name: 'Search' }).click();
    await expect(page).toHaveURL(/\/hotels\?/);
    await page.getByRole('link', { name: hotel.name }).click();
    await expect(page.getByRole('heading', { level: 1, name: hotel.name })).toBeVisible();
    await expect(page.getByText('No reviews yet')).toBeVisible();

    // reserving requires an account: register, then come back via returnUrl
    await page.getByRole('button', { name: /Reserve/ }).first().click();
    await expect(page).toHaveURL(/\/login\?returnUrl=/);
    const email = `browser-${uid()}@example.com`;
    await page.getByRole('link', { name: 'Create one' }).click();
    await page.getByLabel('Full name').fill('Browser Guest');
    await page.getByLabel('Email').fill(email);
    await page.getByLabel('Password', { exact: true }).fill(PASSWORD);
    await page.getByLabel('Confirm password').fill(PASSWORD);
    await page.getByRole('button', { name: 'Create account' }).click();

    // checkout (prices computed by the backend)
    await expect(page.getByRole('heading', { name: 'Complete your booking' })).toBeVisible();
    await expect(page.getByText('Total')).toBeVisible();
    await page.getByLabel('Full name').fill('Browser Guest');
    await page.getByLabel('Email').fill(email);
    await page.getByLabel(/I accept the cancellation policy/).check();
    await page.getByRole('button', { name: 'Reserve now' }).click();

    await expect(page).toHaveURL(/\/bookings\/[0-9a-f-]{36}/);
    await expect(page.getByText(/Your rooms are held for/)).toBeVisible();
    await page.getByRole('button', { name: /^Pay / }).click();

    // provider page (same origin, no script) → success → back to the booking
    await expect(page.getByRole('heading', { name: 'Sandbox Pay' })).toBeVisible();
    await page.getByRole('button', { name: /Pay \(simulate success\)/ }).click();
    await expect(page).toHaveURL(/payment=return/);
    await expect(page.getByText('Payment received — your booking is confirmed.')).toBeVisible({ timeout: 45_000 });
    await expect(page.locator('main .badge')).toHaveText('Confirmed');

    // notifications arrive through Kafka; the bell polls every minute, the page loads on demand
    await page.getByRole('link', { name: /^Notifications/ }).click();
    await expect(page.getByText(/Booking SH-.* confirmed/)).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText(/Booking SH-.* received/)).toBeVisible();
    await page.getByRole('button', { name: 'Mark all as read' }).click();
    await expect(page.getByRole('button', { name: 'Mark all as read' })).toBeHidden();

    // my bookings and cancellation with refund (≥ 48 h before check-in)
    await page.getByRole('link', { name: 'My bookings' }).click();
    await page.getByRole('link', { name: new RegExp(hotel.name) }).click();
    await page.getByRole('button', { name: 'Cancel booking' }).click();
    await page.getByRole('button', { name: 'Yes, cancel booking' }).click();
    await expect(page.locator('main .badge')).toHaveText('Cancelled');
    await expect(page.getByText(/qualifies for a refund/)).toBeVisible();
    // The refund completes asynchronously (booking event → payment → provider webhook → booking). Each reload also
    // proves the session is restored from the HttpOnly refresh cookie.
    await expect(async () => {
      await page.reload();
      await expect(page.getByText(/has been refunded to your payment method/)).toBeVisible({ timeout: 3_000 });
    }).toPass({ timeout: 60_000, intervals: [2_000] });

    await admin.send('PATCH', `/api/v1/hotels/${hotel.id}/status`, { status: 'INACTIVE' });
  });

  test('the access token never reaches web storage', async ({ page, baseURL }) => {
    const { email } = await Api.register(baseURL!, 'storage');
    await page.goto('/login');
    await page.getByLabel('Email').fill(email);
    await page.getByLabel('Password').fill(PASSWORD);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
    const stored = await page.evaluate(() => JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage }));
    expect(stored).not.toMatch(/eyJ/); // no JWT anywhere
    const refresh = (await page.context().cookies()).find((c) => c.name === 'SH_REFRESH');
    expect(refresh?.httpOnly).toBe(true);
  });
});
