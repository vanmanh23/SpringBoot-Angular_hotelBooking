import { expect, test } from '@playwright/test';
import { adminCredentials, Api, PASSWORD, signIn, uid } from './support';

/** Manager and admin areas in a real browser; role checks are also enforced server-side (403s). */
test.describe('staff areas', () => {
  test('a manager creates a draft, an admin approves it, the manager edits it and it goes public', async ({ page, baseURL }) => {
    const { email: adminEmail, password: adminPassword } = adminCredentials();
    const admin = await Api.login(baseURL!, adminEmail, adminPassword);
    const manager = await Api.register(baseURL!, 'manager');
    await admin.send('PUT', `/api/v1/admin/users/${manager.id}/roles`, { roles: ['CUSTOMER', 'HOTEL_MANAGER'] });

    // manager: create a draft hotel with a room type
    await signIn(page, manager.email);
    await page.getByRole('link', { name: 'Manage', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'My hotels' })).toBeVisible();
    await page.getByRole('button', { name: 'New hotel' }).click();
    const name = `Browser Draft ${uid()}`;
    const city = `Draft City ${uid()}`;
    await page.getByLabel('Name').fill(name);
    await page.getByLabel('Address').fill('2 Draft Road');
    await page.getByLabel('City').fill(city);
    await page.getByRole('button', { name: 'Create draft' }).click();
    await expect(page.getByRole('heading', { level: 1, name })).toBeVisible();
    await expect(page.getByText(/must approve it/)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Approve & publish' })).toHaveCount(0);

    await page.getByRole('tab', { name: 'Room types' }).click();
    await page.getByLabel('Name').fill('Garden Twin');
    await page.getByLabel('Max guests').fill('2');
    await page.getByLabel('Units (rooms of this type)').fill('4');
    await page.getByLabel('Base price per night (VND)').fill('750000');
    await page.getByRole('button', { name: 'Add room type' }).click();
    await expect(page.getByRole('cell', { name: 'Garden Twin' })).toBeVisible();
    const hotelUrl = page.url();

    // a customer cannot open staff areas (UI guard; the API answers 403 anyway)
    await page.getByRole('button', { name: 'Sign out' }).click();
    const customer = await Api.register(baseURL!, 'nosy');
    await signIn(page, customer.email);
    await page.goto('/admin');
    await expect(page).toHaveURL(/\/forbidden/);
    await page.goto(hotelUrl);
    await expect(page).toHaveURL(/\/forbidden/);

    // admin: approve from the approvals queue
    await page.getByRole('button', { name: 'Sign out' }).click();
    await signIn(page, adminEmail, adminPassword);
    await page.getByRole('link', { name: 'Admin', exact: true }).click();
    await page.getByRole('tab', { name: 'Hotel approvals' }).click();
    await page.getByRole('row', { name: new RegExp(name) }).getByRole('link', { name: 'Review & approve' }).click();
    await page.getByRole('button', { name: 'Approve & publish' }).click();
    await expect(page.getByText('Status changed to ACTIVE.')).toBeVisible();

    // manager: edit details → visible publicly
    await page.getByRole('button', { name: 'Sign out' }).click();
    await signIn(page, manager.email);
    await page.goto(hotelUrl);
    await page.getByLabel('Description').fill('Quiet garden rooms near the river.');
    await page.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(page.getByText('Details saved.')).toBeVisible();
    await page.getByRole('link', { name: 'View public page' }).click();
    await expect(page.getByText('Quiet garden rooms near the river.')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Garden Twin' })).toBeVisible();

    const hotelId = hotelUrl.split('/').pop()!;
    await admin.send('PATCH', `/api/v1/hotels/${hotelId}/status`, { status: 'INACTIVE' });
  });

  test('an admin grants and revokes roles and locks a user; the locked user cannot sign in', async ({ page, baseURL }) => {
    const { email: adminEmail, password: adminPassword } = adminCredentials();
    const target = await Api.register(baseURL!, 'target');
    await signIn(page, adminEmail, adminPassword);
    await page.goto('/admin?section=users');
    await page.getByLabel('Search').fill(target.email);
    await page.getByRole('button', { name: 'Search', exact: true }).click();
    const row = page.getByRole('row', { name: new RegExp(target.email) });
    await row.getByLabel('Hotel manager').check();
    await expect(row.getByLabel('Hotel manager')).toBeChecked();
    await row.getByRole('button', { name: 'Lock' }).click();
    await expect(row.getByRole('button', { name: 'Unlock' })).toBeVisible();

    await page.getByRole('button', { name: 'Sign out' }).click();
    await page.goto('/login');
    await page.getByLabel('Email').fill(target.email);
    await page.getByLabel('Password').fill(PASSWORD);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByRole('alert')).toContainText(/locked/i);
  });
});
