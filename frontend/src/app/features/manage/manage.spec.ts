import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, provideRouter, RouterStateSnapshot, UrlTree } from '@angular/router';
import { roleGuard } from '../../core/auth/auth.guards';
import { ManagedHotel } from '../../core/manage/manage-api';
import { emptyPage, signInAs } from '../../testing/sign-in';
import { ManageHotelPage, offeredTransitions } from './manage-hotel.page';

const managedHotel = (over: Partial<ManagedHotel> = {}): ManagedHotel => ({
  id: 'h1', name: 'Riverside', description: null, address: '1 Street', city: 'Hue', countryCode: 'VN', starRating: 4,
  timeZone: 'Asia/Ho_Chi_Minh', checkInTime: '14:00', checkOutTime: '12:00', cancellationPolicy: null,
  taxRatePercent: 8, serviceFeePercent: 5, avgRating: null, reviewCount: 0, images: [], amenities: [], roomTypes: [],
  status: 'DRAFT', approvedAt: null, managerIds: ['u-self'], version: 3, ...over,
});

describe('management area', () => {
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it.each([
    ['DRAFT', false, false, []],
    ['DRAFT', true, false, ['ACTIVE', 'SUSPENDED']],
    ['ACTIVE', false, true, ['INACTIVE']],
    ['INACTIVE', false, true, ['ACTIVE']],
    ['INACTIVE', false, false, []],
    ['SUSPENDED', false, true, []],
  ] as const)('offers %s → transitions (admin=%s, approved=%s) like the backend state machine', (status, admin, approved, expected) => {
    expect(offeredTransitions(status, admin, approved)).toEqual(expected);
  });

  it('roleGuard accepts any of the listed roles', async () => {
    const run = () => TestBed.runInInjectionContext(() =>
      roleGuard('HOTEL_MANAGER', 'ADMIN')({} as ActivatedRouteSnapshot, { url: '/manage' } as RouterStateSnapshot));
    await signInAs(backend, ['CUSTOMER']);
    expect((run() as UrlTree).toString()).toBe('/forbidden');
    await signInAs(backend, ['CUSTOMER', 'HOTEL_MANAGER']);
    expect(run()).toBe(true);
  });

  it('a manager edits details with the optimistic-lock version and sees a conflict explained', async () => {
    await signInAs(backend, ['HOTEL_MANAGER']);
    const fixture = TestBed.createComponent(ManageHotelPage);
    fixture.componentRef.setInput('id', 'h1');
    fixture.detectChanges();
    backend.expectOne('/api/v1/manage/hotels/h1').flush(managedHotel());
    backend.expectOne('/api/v1/amenities?category=HOTEL').flush([]);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    expect(el.textContent).toContain('must approve it'); // draft notice for managers
    expect(el.textContent).not.toContain('Approve & publish'); // only admins approve
    expect(el.textContent).not.toContain('Managers'); // admin-only tab

    el.querySelector<HTMLButtonElement>('app-hotel-form button[type=submit]')!.click();
    const put = backend.expectOne('/api/v1/hotels/h1');
    expect(put.request.method).toBe('PUT');
    expect(put.request.body.version).toBe(3);
    put.flush({ status: 409, code: 'CONCURRENT_MODIFICATION', message: 'x', fieldErrors: [] }, { status: 409, statusText: 'Conflict' });
    backend.expectOne('/api/v1/manage/hotels/h1').flush(managedHotel({ version: 4 }));
    fixture.detectChanges();
    expect(el.textContent).toContain('Someone else changed this hotel');
  });

  it('an admin can approve a draft and open the managers tab', async () => {
    await signInAs(backend, ['ADMIN']);
    const fixture = TestBed.createComponent(ManageHotelPage);
    fixture.componentRef.setInput('id', 'h1');
    fixture.detectChanges();
    backend.expectOne('/api/v1/manage/hotels/h1').flush(managedHotel());
    backend.expectOne('/api/v1/amenities?category=HOTEL').flush([]);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    [...el.querySelectorAll('button')].find((b) => b.textContent?.includes('Approve & publish'))!.click();
    const patch = backend.expectOne('/api/v1/hotels/h1/status');
    expect(patch.request.body).toEqual({ status: 'ACTIVE' });
    patch.flush(managedHotel({ status: 'ACTIVE', approvedAt: '2026-10-19T00:00:00Z' }));
    fixture.detectChanges();
    expect(el.textContent).toContain('Status changed to ACTIVE');

    [...el.querySelectorAll('[role=tab]')].find((t) => t.textContent?.includes('Managers'))!.dispatchEvent(new Event('click'));
    fixture.detectChanges();
    expect(el.textContent).toContain('u-self');
  });

  it('a manager who is not assigned gets a clear message (403 from the backend)', async () => {
    await signInAs(backend, ['HOTEL_MANAGER']);
    const fixture = TestBed.createComponent(ManageHotelPage);
    fixture.componentRef.setInput('id', 'h2');
    fixture.detectChanges();
    backend.expectOne('/api/v1/manage/hotels/h2')
      .flush({ status: 403, code: 'FORBIDDEN', message: 'x', fieldErrors: [] }, { status: 403, statusText: 'Forbidden' });
    backend.expectOne('/api/v1/amenities?category=HOTEL').flush([]);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('You are not assigned to this hotel');
  });

  it('the bookings tab queries only that hotel with the chosen filters', async () => {
    await signInAs(backend, ['HOTEL_MANAGER']);
    const fixture = TestBed.createComponent(ManageHotelPage);
    fixture.componentRef.setInput('id', 'h1');
    fixture.detectChanges();
    backend.expectOne('/api/v1/manage/hotels/h1').flush(managedHotel({ status: 'ACTIVE' }));
    backend.expectOne('/api/v1/amenities?category=HOTEL').flush([]);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    [...el.querySelectorAll('[role=tab]')].find((t) => t.textContent?.includes('Bookings'))!.dispatchEvent(new Event('click'));
    fixture.detectChanges();
    backend.expectOne('/api/v1/manage/hotels/h1/bookings?page=0&size=20').flush(emptyPage);

    el.querySelector<HTMLSelectElement>('#mb-status')!.value = 'CONFIRMED';
    el.querySelector<HTMLInputElement>('#mb-from')!.value = '2026-11-01';
    [...el.querySelectorAll('button')].find((b) => b.textContent?.includes('Filter'))!.click();
    backend.expectOne('/api/v1/manage/hotels/h1/bookings?page=0&size=20&status=CONFIRMED&from=2026-11-01').flush(emptyPage);
  });
});
