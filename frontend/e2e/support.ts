import AxeBuilder from '@axe-core/playwright';
import { APIRequestContext, expect, Page, request } from '@playwright/test';

/** Test data is created through the public API with the bootstrap admin; every name is unique per run. */
export const PASSWORD = 'Passw0rd-e2e';
export const uid = () => Math.random().toString(36).slice(2, 10);

export function adminCredentials(): { email: string; password: string } {
  const email = process.env['E2E_ADMIN_EMAIL'];
  const password = process.env['E2E_ADMIN_PASSWORD'];
  if (!email || !password) throw new Error('Set E2E_ADMIN_EMAIL and E2E_ADMIN_PASSWORD (the ADMIN_* values of .env)');
  return { email, password };
}

export class Api {
  private constructor(private readonly ctx: APIRequestContext, private readonly token: string) {}

  static async login(baseURL: string, email: string, password: string): Promise<Api> {
    const ctx = await request.newContext({ baseURL });
    const res = await ctx.post('/api/v1/auth/login', { data: { email, password } });
    expect(res.status(), await res.text()).toBe(200);
    return new Api(ctx, (await res.json()).accessToken);
  }

  static async register(baseURL: string, prefix: string): Promise<{ email: string; id: string }> {
    const ctx = await request.newContext({ baseURL });
    const email = `${prefix}-${uid()}@example.com`;
    const res = await ctx.post('/api/v1/auth/register', { data: { email, password: PASSWORD, fullName: `E2E ${prefix}` } });
    expect(res.status(), await res.text()).toBe(201);
    const id = (await res.json()).id;
    await ctx.dispose();
    return { email, id };
  }

  async send<T = unknown>(method: string, path: string, data?: unknown): Promise<T> {
    const res = await this.ctx.fetch(path, { method, data, headers: { Authorization: `Bearer ${this.token}` } });
    expect(res.ok(), `${method} ${path} -> ${res.status()} ${await res.text()}`).toBe(true);
    return (res.status() === 204 ? undefined : await res.json()) as T;
  }

  /** An ACTIVE hotel in a unique city with one bookable room type. */
  async activeHotel(name = `E2E Browser Hotel ${uid()}`): Promise<{ id: string; name: string; city: string }> {
    const city = `E2E City ${uid()}`;
    const hotel = await this.send<{ id: string }>('POST', '/api/v1/hotels', { name, address: '1 Browser St', city, countryCode: 'VN', starRating: 4 });
    await this.send('POST', `/api/v1/hotels/${hotel.id}/room-types`, { name: 'Deluxe Double', capacity: 2, basePrice: 900000, totalUnits: 5 });
    await this.send('PATCH', `/api/v1/hotels/${hotel.id}/status`, { status: 'ACTIVE' });
    return { id: hotel.id, name, city };
  }
}

export async function signIn(page: Page, email: string, password = PASSWORD): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
}

export function isoDate(daysFromToday: number): string {
  const d = new Date();
  d.setDate(d.getDate() + daysFromToday);
  return d.toLocaleDateString('en-CA', { timeZone: 'Asia/Ho_Chi_Minh' });
}

/** WCAG 2.1 A/AA automated checks (axe); returns violations for a readable assertion message. */
export async function axeViolations(page: Page): Promise<string[]> {
  const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  return result.violations.map((v) => `${v.id} (${v.impact}): ${v.nodes.slice(0, 3).map((n) => n.target.join(' ')).join(' | ')}`);
}

export async function expectNoHorizontalScroll(page: Page): Promise<void> {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow, 'page must not scroll horizontally').toBeLessThanOrEqual(1);
}
