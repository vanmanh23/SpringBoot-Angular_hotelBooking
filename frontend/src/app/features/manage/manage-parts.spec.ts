import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ManagedHotel } from '../../core/manage/manage-api';
import { emptyPage, signInAs } from '../../testing/sign-in';
import { HotelManagers } from './hotel-managers';
import { ManageRoomTypes } from './manage-room-types';

const hotel = (over: Partial<ManagedHotel> = {}): ManagedHotel => ({
  id: 'h1', name: 'Riverside', description: null, address: '1 Street', city: 'Hue', countryCode: 'VN', starRating: 4,
  timeZone: 'Asia/Ho_Chi_Minh', checkInTime: '14:00', checkOutTime: '12:00', cancellationPolicy: null,
  taxRatePercent: 8, serviceFeePercent: 5, avgRating: null, reviewCount: 0, images: [], amenities: [],
  roomTypes: [{ id: 'rt1', hotelId: 'h1', name: 'Double', description: null, capacity: 2, bedDescription: null, basePrice: 900_000,
    currency: 'VND', totalUnits: 3, status: 'ACTIVE', amenities: [], version: 7 }],
  status: 'ACTIVE', approvedAt: '2026-01-01T00:00:00Z', managerIds: ['m1'], version: 1, ...over,
});

function input(el: HTMLElement, selector: string, value: string): void {
  const field = el.querySelector<HTMLInputElement | HTMLTextAreaElement>(selector)!;
  field.value = value;
  field.dispatchEvent(new Event('input'));
}

describe('management parts', () => {
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  describe('ManageRoomTypes', () => {
    function create() {
      const fixture = TestBed.createComponent(ManageRoomTypes);
      fixture.componentRef.setInput('hotel', hotel());
      fixture.detectChanges();
      const changed = vi.fn();
      fixture.componentInstance.changed.subscribe(changed);
      return { fixture, el: fixture.nativeElement as HTMLElement, changed };
    }

    it('adds a room type with whole-number values and refuses invalid input client-side', () => {
      const { fixture, el, changed } = create();
      el.querySelector<HTMLButtonElement>('button[type=submit]')!.click();
      fixture.detectChanges();
      backend.expectNone('/api/v1/hotels/h1/room-types'); // name and price required
      expect(el.textContent).toContain('This field is required.');

      input(el, '#rt-name', ' Family ');
      input(el, '#rt-capacity', '4');
      input(el, '#rt-units', '2');
      input(el, '#rt-price', '1500000');
      fixture.detectChanges();
      el.querySelector<HTMLButtonElement>('button[type=submit]')!.click();
      const req = backend.expectOne('/api/v1/hotels/h1/room-types');
      expect(req.request.body).toEqual({ name: 'Family', capacity: 4, totalUnits: 2, basePrice: 1500000,
        bedDescription: null, description: null, version: undefined });
      req.flush({});
      expect(changed).toHaveBeenCalled();
    });

    it('edits with the optimistic-lock version and explains duplicate names and conflicts', () => {
      const { fixture, el, changed } = create();
      [...el.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Edit')!.click();
      fixture.detectChanges();
      expect(el.querySelector<HTMLInputElement>('#rt-name')!.value).toBe('Double');

      el.querySelector<HTMLButtonElement>('button[type=submit]')!.click();
      const put = backend.expectOne('/api/v1/room-types/rt1');
      expect(put.request.method).toBe('PUT');
      expect(put.request.body.version).toBe(7);
      put.flush({ status: 409, code: 'ROOM_TYPE_NAME_TAKEN', message: 'x', fieldErrors: [] }, { status: 409, statusText: 'Conflict' });
      fixture.detectChanges();
      expect(el.textContent).toContain('A room type with this name already exists.');

      el.querySelector<HTMLButtonElement>('button[type=submit]')!.click();
      backend.expectOne('/api/v1/room-types/rt1')
        .flush({ status: 409, code: 'CONCURRENT_MODIFICATION', message: 'x', fieldErrors: [] }, { status: 409, statusText: 'Conflict' });
      fixture.detectChanges();
      expect(el.textContent).toContain('changed meanwhile');
      expect(changed).toHaveBeenCalledTimes(1); // reload requested to get the new version
    });

    it('deactivates a room type', () => {
      const { el, changed } = create();
      [...el.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Deactivate')!.click();
      const patch = backend.expectOne('/api/v1/room-types/rt1/status');
      expect(patch.request.body).toEqual({ status: 'INACTIVE' });
      patch.flush({});
      expect(changed).toHaveBeenCalled();
    });
  });

  describe('HotelManagers', () => {
    const user = (id: string, roles: string[]) => ({ id, email: id + '@example.com', fullName: 'U ' + id, phone: null,
      status: 'ACTIVE', roles, createdAt: '' });

    it('only offers users with the manager role and shows the backend refusal', async () => {
      await signInAs(backend, ['ADMIN']);
      const fixture = TestBed.createComponent(HotelManagers);
      fixture.componentRef.setInput('hotel', hotel());
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;
      expect(el.textContent).toContain('m1');

      input(el, '#mgr-q', 'lan');
      [...el.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Search')!.click();
      backend.expectOne('/api/v1/admin/users?page=0&size=20&q=lan&status=ACTIVE').flush({ ...emptyPage,
        content: [user('c1', ['CUSTOMER']), user('m1', ['HOTEL_MANAGER']), user('m2', ['CUSTOMER', 'HOTEL_MANAGER'])] });
      fixture.detectChanges();
      expect(el.textContent).not.toContain('c1@example.com');
      expect(el.textContent).toContain('assigned'); // m1 already assigned

      [...el.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Assign')!.click();
      backend.expectOne('/api/v1/hotels/h1/managers').flush(
        { status: 422, code: 'USER_NOT_HOTEL_MANAGER', message: 'Only active users with the Hotel manager role can be assigned', fieldErrors: [] },
        { status: 422, statusText: 'Unprocessable' });
      fixture.detectChanges();
      expect(el.textContent).toContain('Only active users with the Hotel manager role');

      [...el.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Remove')!.click();
      expect(backend.expectOne('/api/v1/hotels/h1/managers/m1').request.method).toBe('DELETE');
    });
  });
});
