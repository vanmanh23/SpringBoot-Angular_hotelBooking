import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { HotelDetail, RoomType } from '../../core/hotels/hotel.models';
import { HotelDetailPage } from './hotel-detail.page';

const room = (id: string, name: string, capacity: number, price: number): RoomType => ({
  id,
  hotelId: 'h1',
  name,
  description: null,
  capacity,
  bedDescription: null,
  basePrice: price,
  currency: 'VND',
  totalUnits: 3,
  status: 'ACTIVE',
  amenities: [],
});

const detail: HotelDetail = {
  id: 'h1',
  name: 'Sea View Hotel',
  description: 'By the beach',
  address: '1 Beach Rd',
  city: 'Đà Nẵng',
  countryCode: 'VN',
  starRating: 4,
  timeZone: 'Asia/Ho_Chi_Minh',
  checkInTime: '14:00',
  checkOutTime: '12:00',
  cancellationPolicy: null,
  taxRatePercent: 8,
  serviceFeePercent: 5,
  avgRating: null,
  reviewCount: 0,
  images: [{ id: 'i1', url: 'https://img.example/1.jpg', caption: null }],
  amenities: [{ code: 'WIFI', name: 'Free Wi-Fi' }],
  roomTypes: [room('r1', 'Double', 2, 900_000), room('r2', 'Family', 4, 1_500_000)],
};

describe('HotelDetailPage', () => {
  let backend: HttpTestingController;
  let harness: RouterTestingHarness;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'hotels/:id', component: HotelDetailPage }], withComponentInputBinding()),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => backend.verify());

  const text = () => (harness.routeNativeElement as HTMLElement).textContent ?? '';

  it('renders hotel data and all active room types; booking needs dates first', async () => {
    await harness.navigateByUrl('/hotels/h1');
    backend.expectOne('/api/v1/hotels/h1').flush(detail);
    harness.detectChanges();

    expect(text()).toContain('Sea View Hotel');
    expect(text()).toContain('Check-in from 14:00');
    expect(text()).toContain('Double');
    expect(text()).toContain('Family');
    expect(text()).toContain('5% service fee and 8% VAT');
    const book = (harness.routeNativeElement as HTMLElement).querySelector<HTMLButtonElement>('.room-price button')!;
    expect(book.textContent).toContain('Choose dates');
    expect(book.disabled).toBe(true);
  });

  it('shows availability and sends anonymous users to login with a return URL to checkout', async () => {
    await harness.navigateByUrl('/hotels/h1?checkIn=2030-01-10&checkOut=2030-01-12');
    backend.expectOne('/api/v1/hotels/h1').flush(detail);
    backend.expectOne('/api/v1/availability?hotelId=h1&checkIn=2030-01-10&checkOut=2030-01-12').flush({
      hotelId: 'h1', checkIn: '2030-01-10', checkOut: '2030-01-12',
      roomTypes: [{ roomTypeId: 'r1', totalUnits: 3, availableUnits: 0 }, { roomTypeId: 'r2', totalUnits: 3, availableUnits: 2 }],
    });
    harness.detectChanges();

    expect(text()).toContain('Sold out for these dates');
    expect(text()).toContain('Only 2 left');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    [...(harness.routeNativeElement as HTMLElement).querySelectorAll('button')].find((b) => b.textContent?.includes('Reserve'))!.click();

    const [commands, extras] = navigate.mock.calls[0];
    expect(commands).toEqual(['/login']);
    expect(String(extras?.queryParams?.['returnUrl'])).toMatch(/^\/booking\/new\?.*roomTypeId=r2/);
  });

  it('asks the backend which room types fit the party when guests are given', async () => {
    await harness.navigateByUrl('/hotels/h1?guests=3&rooms=1&checkIn=2030-01-10&checkOut=2030-01-13');
    backend.expectOne('/api/v1/hotels/h1').flush(detail);
    backend.expectOne('/api/v1/hotels/h1/room-types?guests=3&rooms=1').flush([room('r2', 'Family', 4, 1_500_000)]);
    backend.expectOne((r) => r.url === '/api/v1/availability').flush({ hotelId: 'h1', checkIn: '', checkOut: '', roomTypes: [] });
    harness.detectChanges();

    expect(text()).toContain('3 nights');
    expect(text()).toContain('Family');
    expect(text()).not.toContain('Double');
  });

  it('shows a not-found state for unknown or hidden hotels', async () => {
    await harness.navigateByUrl('/hotels/missing');
    backend.expectOne('/api/v1/hotels/missing').flush(
      { code: 'HOTEL_NOT_FOUND', message: 'Hotel not found' },
      { status: 404, statusText: 'Not Found' },
    );
    harness.detectChanges();

    expect(text()).toContain('Hotel not found');
  });
});
