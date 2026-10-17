import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
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

  it('renders hotel data and all active room types; booking is not offered yet', async () => {
    await harness.navigateByUrl('/hotels/h1');
    backend.expectOne('/api/v1/hotels/h1').flush(detail);
    harness.detectChanges();

    expect(text()).toContain('Sea View Hotel');
    expect(text()).toContain('Check-in from 14:00');
    expect(text()).toContain('Double');
    expect(text()).toContain('Family');
    const book = (harness.routeNativeElement as HTMLElement).querySelector<HTMLButtonElement>('.room-price button')!;
    expect(book.disabled).toBe(true);
  });

  it('asks the backend which room types fit the party when guests are given', async () => {
    await harness.navigateByUrl('/hotels/h1?guests=3&rooms=1&checkIn=2030-01-10&checkOut=2030-01-13');
    backend.expectOne('/api/v1/hotels/h1').flush(detail);
    backend.expectOne('/api/v1/hotels/h1/room-types?guests=3&rooms=1').flush([room('r2', 'Family', 4, 1_500_000)]);
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
