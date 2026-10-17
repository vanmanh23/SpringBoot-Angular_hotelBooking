import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting, TestRequest } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Quote } from '../../core/bookings/booking.models';
import { CheckoutPage } from './checkout.page';

const quote: Quote = {
  hotelId: 'h1',
  hotelName: 'Sea View',
  checkInDate: '2030-01-10',
  checkOutDate: '2030-01-12',
  nights: 2,
  currency: 'VND',
  lines: [{ roomTypeId: 'r1', roomTypeName: 'Double', quantity: 1, unitPrice: 1_000_000, nights: 2, lineSubtotal: 2_000_000 }],
  subtotal: 2_000_000,
  serviceFeePercent: 5,
  feeAmount: 100_000,
  taxRatePercent: 8,
  taxAmount: 168_000,
  discountAmount: 0,
  totalAmount: 2_268_000,
  available: true,
};

const URL = '/booking/new?hotelId=h1&roomTypeId=r1&quantity=1&checkIn=2030-01-10&checkOut=2030-01-12&guests=2';

describe('CheckoutPage', () => {
  let backend: HttpTestingController;
  let harness: RouterTestingHarness;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'booking/new', component: CheckoutPage }], withComponentInputBinding()),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => backend.verify());

  /** Real timers: rxjs debounceTime and vitest fake timers do not mix well ("executing a cancelled action"). */
  const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

  const el = () => harness.routeNativeElement as HTMLElement;

  async function openAndQuote(): Promise<TestRequest> {
    await harness.navigateByUrl(URL);
    harness.detectChanges();
    await wait(350);
    const req = backend.expectOne('/api/v1/bookings/quote');
    return req;
  }

  function fillContact() {
    const set = (id: string, value: string) => {
      const input = el().querySelector<HTMLInputElement>(`#${id}`)!;
      input.value = value;
      input.dispatchEvent(new Event('input'));
    };
    set('contactName', 'Nguyen Van A');
    set('contactEmail', 'a@example.com');
    el().querySelector<HTMLInputElement>('input[type=checkbox]')!.click();
  }

  it('asks the backend for the price and never sends amounts', async () => {
    const req = await openAndQuote();
    expect(req.request.body).toMatchObject({ hotelId: 'h1', adults: 2, rooms: [{ roomTypeId: 'r1', quantity: 1 }] });
    expect(JSON.stringify(req.request.body)).not.toMatch(/amount|price|subtotal/i);
    req.flush(quote);
    harness.detectChanges();

    expect(el().textContent).toMatch(/2\.268\.000\s?₫/);
    expect(el().textContent).toContain('VAT (8%)');
  });

  it('creates the booking with an Idempotency-Key and reuses it when retrying after a network error', async () => {
    (await openAndQuote()).flush(quote);
    harness.detectChanges();
    fillContact();
    await wait(350);
    backend.match('/api/v1/bookings/quote').forEach((r) => r.flush(quote));
    harness.detectChanges();

    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    const first = backend.expectOne((r) => r.url === '/api/v1/bookings' && r.method === 'POST');
    const key = first.request.headers.get('Idempotency-Key');
    expect(key).toBeTruthy();
    first.error(new ProgressEvent('error'), { status: 0, statusText: 'Network error' });
    harness.detectChanges();
    expect(el().textContent).toContain('Cannot reach the server');

    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    const retry = backend.expectOne((r) => r.url === '/api/v1/bookings' && r.method === 'POST');
    expect(retry.request.headers.get('Idempotency-Key')).toBe(key);
    retry.flush({ id: 'b1' });
    await wait(0);
    expect(navigate).toHaveBeenCalledWith(['/bookings', 'b1'], { replaceUrl: true });
  });

  it('explains a sold-out race and blocks double submission', async () => {
    (await openAndQuote()).flush(quote);
    harness.detectChanges();
    fillContact();
    await wait(350);
    backend.match('/api/v1/bookings/quote').forEach((r) => r.flush(quote));
    harness.detectChanges();

    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    const requests = backend.match((r) => r.url === '/api/v1/bookings' && r.method === 'POST');
    expect(requests.length).toBe(1);
    requests[0].flush({ code: 'ROOM_SOLD_OUT', message: 'x' }, { status: 409, statusText: 'Conflict' });
    harness.detectChanges();

    expect(el().textContent).toContain('just booked by someone else');
    expect(el().textContent).toContain('Choose another room');
  });

  it('requires accepting the cancellation policy', async () => {
    (await openAndQuote()).flush(quote);
    harness.detectChanges();
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    harness.detectChanges();
    backend.expectNone((r) => r.url === '/api/v1/bookings' && r.method === 'POST');
  });
});
