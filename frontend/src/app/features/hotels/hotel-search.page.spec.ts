import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting, TestRequest } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { HotelSummary } from '../../core/hotels/hotel.models';
import { HotelSearchPage } from './hotel-search.page';

const hotel = (id: string, name: string): HotelSummary => ({
  id,
  name,
  city: 'Đà Nẵng',
  address: '1 Beach Rd',
  countryCode: 'VN',
  starRating: 4,
  avgRating: null,
  reviewCount: 0,
  thumbnailUrl: null,
  fromPrice: 1_200_000,
  currency: 'VND',
  amenities: ['WIFI'],
});

const page = (content: HotelSummary[], totalPages = 1) => ({
  content,
  page: 0,
  size: 10,
  totalElements: content.length,
  totalPages,
});

describe('HotelSearchPage', () => {
  let backend: HttpTestingController;
  let harness: RouterTestingHarness;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'hotels', component: HotelSearchPage }]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  });

  afterEach(() => backend.verify());

  const searchRequest = (): TestRequest =>
    backend.expectOne((r) => r.url === '/api/v1/hotels' && r.method === 'GET');
  const flushAmenities = () =>
    backend.expectOne('/api/v1/amenities?category=HOTEL').flush([{ id: '1', code: 'WIFI', name: 'Free Wi-Fi', category: 'HOTEL' }]);
  const text = () => (harness.routeNativeElement as HTMLElement).textContent ?? '';

  it('sends the URL criteria to the API and renders results with formatted prices', async () => {
    await harness.navigateByUrl('/hotels?city=Da%20Nang&guests=2&stars=4&sort=price_asc');
    flushAmenities();
    const req = searchRequest();
    expect(req.request.params.get('city')).toBe('Da Nang');
    expect(req.request.params.get('guests')).toBe('2');
    expect(req.request.params.get('stars')).toBe('4');
    expect(req.request.params.get('sort')).toBe('price_asc');
    expect(req.request.params.get('size')).toBe('10');
    req.flush(page([hotel('h1', 'Sea View'), hotel('h2', 'City Inn')]));
    harness.detectChanges();

    expect(text()).toContain('2 hotels');
    expect(text()).toContain('Sea View');
    expect(text()).toContain('Free Wi-Fi');
    expect(text()).toMatch(/1\.200\.000\s?₫/);
  });

  it('shows an empty state with a way out', async () => {
    await harness.navigateByUrl('/hotels?city=Nowhere');
    flushAmenities();
    searchRequest().flush(page([]));
    harness.detectChanges();

    expect(text()).toContain('No hotels match your search');
    expect((harness.routeNativeElement as HTMLElement).querySelector('button')?.textContent).toBeDefined();
  });

  it('shows an error and retries on demand', async () => {
    await harness.navigateByUrl('/hotels');
    flushAmenities();
    searchRequest().flush({ code: 'INTERNAL_ERROR', message: 'Unexpected server error' }, { status: 500, statusText: 'Error' });
    harness.detectChanges();
    expect(text()).toContain('Unexpected server error');

    const retry = [...(harness.routeNativeElement as HTMLElement).querySelectorAll('button')].find((b) =>
      b.textContent?.includes('Try again'),
    )!;
    retry.click();
    searchRequest().flush(page([hotel('h1', 'Recovered Hotel')]));
    harness.detectChanges();
    expect(text()).toContain('Recovered Hotel');
  });

  it('resets to the first page when a filter changes', async () => {
    await harness.navigateByUrl('/hotels?city=Hue&page=2');
    flushAmenities();
    expect(searchRequest().request.params.get('page')).toBe('2');

    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    harness.detectChanges();
    const starBox = (harness.routeNativeElement as HTMLElement).querySelector<HTMLInputElement>('input[type=checkbox]')!;
    starBox.click();

    const [, extras] = navigate.mock.calls[0];
    expect(extras?.queryParams).toEqual({ city: 'Hue', stars: '5' });
  });
});
