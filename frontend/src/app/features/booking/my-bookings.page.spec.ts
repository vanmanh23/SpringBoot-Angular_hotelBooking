import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MyBookingsPage } from './my-bookings.page';

describe('MyBookingsPage', () => {
  let backend: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyBookingsPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('lists bookings and filters by status from the first page', async () => {
    const fixture = TestBed.createComponent(MyBookingsPage);
    fixture.detectChanges();
    await fixture.whenStable();
    backend.expectOne('/api/v1/bookings/me?page=0&size=10').flush({
      content: [{ id: 'b1', bookingCode: 'SH-1', status: 'CONFIRMED', hotelId: 'h', hotelName: 'Sea View', checkInDate: '2030-01-10',
        checkOutDate: '2030-01-12', nights: 2, totalAmount: 2_268_000, currency: 'VND', expiresAt: null, createdAt: '' }],
      page: 0, size: 10, totalElements: 1, totalPages: 1,
    });
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Sea View');
    expect(el.textContent).toContain('Confirmed');

    [...el.querySelectorAll('[role=tab]')].find((t) => t.textContent?.includes('Cancelled'))!.dispatchEvent(new Event('click'));
    fixture.detectChanges();
    await fixture.whenStable();
    backend.expectOne('/api/v1/bookings/me?page=0&size=10&status=CANCELLED').flush({
      content: [], page: 0, size: 10, totalElements: 0, totalPages: 0,
    });
    fixture.detectChanges();
    expect(el.textContent).toContain('No bookings here yet');
  });
});
