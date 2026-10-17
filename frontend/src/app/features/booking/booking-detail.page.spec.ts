import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Booking } from '../../core/bookings/booking.models';
import { BookingDetailPage } from './booking-detail.page';

const booking = (overrides: Partial<Booking> = {}): Booking => ({
  id: 'b1',
  bookingCode: 'SH-300110-ABCDEFGH',
  status: 'PENDING_PAYMENT',
  hotelId: 'h1',
  hotelName: 'Sea View',
  hotelTimeZone: 'Asia/Ho_Chi_Minh',
  checkInDate: '2030-01-10',
  checkOutDate: '2030-01-12',
  checkInTime: '14:00',
  nights: 2,
  adults: 2,
  children: 0,
  lines: [{ roomTypeId: 'r1', roomTypeName: 'Double', quantity: 1, unitPrice: 1_000_000, nights: 2, lineSubtotal: 2_000_000 }],
  subtotal: 2_000_000,
  serviceFeePercent: 5,
  feeAmount: 100_000,
  taxRatePercent: 8,
  taxAmount: 168_000,
  discountAmount: 0,
  totalAmount: 2_268_000,
  currency: 'VND',
  contactName: 'A',
  contactEmail: 'a@example.com',
  contactPhone: null,
  specialRequests: null,
  expiresAt: new Date(Date.now() + 10 * 60_000).toISOString(),
  confirmedAt: null,
  cancelledAt: null,
  cancelReason: null,
  refundEligible: null,
  freeCancellationUntil: '2030-01-08T07:00:00Z',
  createdAt: new Date().toISOString(),
  history: [{ fromStatus: null, toStatus: 'PENDING_PAYMENT', reason: 'Booking created', actor: 'u1', at: new Date().toISOString() }],
  ...overrides,
});

describe('BookingDetailPage', () => {
  let backend: HttpTestingController;
  let harness: RouterTestingHarness;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'bookings/:id', component: BookingDetailPage }], withComponentInputBinding()),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => backend.verify());

  const el = () => harness.routeNativeElement as HTMLElement;
  const button = (text: string) => [...el().querySelectorAll('button')].find((b) => b.textContent?.includes(text))!;

  it('shows the hold countdown and the backend price breakdown for a pending booking', async () => {
    await harness.navigateByUrl('/bookings/b1');
    backend.expectOne('/api/v1/bookings/b1').flush(booking());
    harness.detectChanges();

    expect(el().textContent).toContain('Awaiting payment');
    expect(el().textContent).toMatch(/held for\s+(9|10):\d{2}/);
    expect(el().textContent).toMatch(/2\.268\.000\s?₫/);
    expect(button('Pay now').disabled).toBe(true);
  });

  it('cancels only after confirmation and shows the updated status', async () => {
    await harness.navigateByUrl('/bookings/b1');
    backend.expectOne('/api/v1/bookings/b1').flush(booking({ status: 'CONFIRMED', expiresAt: null }));
    harness.detectChanges();

    button('Cancel booking').click();
    harness.detectChanges();
    backend.expectNone('/api/v1/bookings/b1/cancel');

    button('Yes, cancel booking').click();
    const req = backend.expectOne('/api/v1/bookings/b1/cancel');
    expect(req.request.body).toEqual({ reason: null });
    req.flush(booking({ status: 'CANCELLED', expiresAt: null, cancelledAt: new Date().toISOString(), refundEligible: true }));
    harness.detectChanges();

    expect(el().textContent).toContain('Cancelled');
    expect(el().textContent).toContain('qualifies for a refund');
  });

  it('reloads the booking when a cancel races with another status change', async () => {
    await harness.navigateByUrl('/bookings/b1');
    backend.expectOne('/api/v1/bookings/b1').flush(booking());
    harness.detectChanges();
    button('Cancel booking').click();
    harness.detectChanges();
    button('Yes, cancel booking').click();
    backend.expectOne('/api/v1/bookings/b1/cancel').flush(
      { code: 'INVALID_STATUS_TRANSITION', message: 'Booking cannot change from EXPIRED to CANCELLED' },
      { status: 409, statusText: 'Conflict' },
    );
    backend.expectOne('/api/v1/bookings/b1').flush(booking({ status: 'EXPIRED' }));
    harness.detectChanges();

    expect(el().textContent).toContain('Expired');
  });
});
