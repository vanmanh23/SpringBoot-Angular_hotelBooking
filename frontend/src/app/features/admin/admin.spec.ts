import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { User } from '../../core/auth/auth.models';
import { emptyPage, signInAs } from '../../testing/sign-in';
import { AdminPayments } from './admin-transactions';
import { AdminUsers } from './admin-users';

const user = (id: string, over: Partial<User> = {}): User => ({
  id, email: id + '@example.com', fullName: 'User ' + id, phone: null, status: 'ACTIVE', roles: ['CUSTOMER'], createdAt: '2026-01-01T00:00:00Z', ...over,
});

describe('admin area', () => {
  let backend: HttpTestingController;

  beforeEach(async () => {
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    backend = TestBed.inject(HttpTestingController);
    await signInAs(backend, ['ADMIN'], 'admin-1');
  });

  afterEach(() => backend.verify());

  it('grants roles and locks users, but never offers to modify the signed-in admin', () => {
    const fixture = TestBed.createComponent(AdminUsers);
    fixture.detectChanges();
    backend.expectOne('/api/v1/admin/users?page=0&size=20')
      .flush({ ...emptyPage, content: [user('admin-1', { roles: ['ADMIN'] }), user('u2')], totalElements: 2, totalPages: 1 });
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const rows = el.querySelectorAll('tbody tr');

    expect(rows[0].textContent).toContain('you');
    expect([...rows[0].querySelectorAll<HTMLInputElement>('input[type=checkbox]')].every((c) => c.disabled)).toBe(true);

    const managerBox = [...rows[1].querySelectorAll('label')].find((l) => l.textContent?.includes('Hotel manager'))!.querySelector('input')!;
    managerBox.click();
    const put = backend.expectOne('/api/v1/admin/users/u2/roles');
    expect(put.request.body).toEqual({ roles: ['CUSTOMER', 'HOTEL_MANAGER'] });
    put.flush(user('u2', { roles: ['CUSTOMER', 'HOTEL_MANAGER'] }));

    [...rows[1].querySelectorAll('button')].find((b) => b.textContent?.includes('Lock'))!.click();
    const patch = backend.expectOne('/api/v1/admin/users/u2/status');
    expect(patch.request.body.status).toBe('LOCKED');
    patch.flush(user('u2', { status: 'LOCKED' }));
    fixture.detectChanges();
    expect(el.querySelectorAll('tbody tr')[1].textContent).toContain('Unlock');
  });

  it('a failed load shows the error with a working retry', () => {
    const fixture = TestBed.createComponent(AdminUsers);
    fixture.detectChanges();
    backend.expectOne('/api/v1/admin/users?page=0&size=20')
      .flush({ status: 503, code: 'SERVICE_UNAVAILABLE', message: 'Service unavailable', fieldErrors: [] }, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[role=alert]')?.textContent).toContain('Service unavailable');

    [...el.querySelectorAll('button')].find((b) => b.textContent?.includes('Try again'))!.click();
    backend.expectOne('/api/v1/admin/users?page=0&size=20').flush({ ...emptyPage, content: [user('u2')], totalElements: 1, totalPages: 1 });
    fixture.detectChanges();
    expect(el.querySelector('[role=alert]')).toBeNull();
    expect(el.textContent).toContain('User u2');
  });

  it('refuses to remove the last role client-side and shows the backend error otherwise', () => {
    const fixture = TestBed.createComponent(AdminUsers);
    fixture.detectChanges();
    backend.expectOne('/api/v1/admin/users?page=0&size=20')
      .flush({ ...emptyPage, content: [user('u2')], totalElements: 1, totalPages: 1 });
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    [...el.querySelectorAll('label')].find((l) => l.textContent?.includes('Customer'))!.querySelector('input')!.click();
    fixture.detectChanges();
    expect(el.textContent).toContain('at least one role');
    backend.expectNone('/api/v1/admin/users/u2/roles');
  });

  it('filters the payment queue by "needs review" and requests a refund for a captured payment', () => {
    const fixture = TestBed.createComponent(AdminPayments);
    fixture.detectChanges();
    backend.expectOne('/api/v1/admin/payments?page=0&size=20').flush(emptyPage);
    const el = fixture.nativeElement as HTMLElement;

    el.querySelector<HTMLInputElement>('input[type=checkbox]')!.checked = true;
    [...el.querySelectorAll('button')].find((b) => b.textContent?.includes('Filter'))!.click();
    const payment = { id: 'p1', bookingId: 'b1234567890', amount: 1_000_000, currency: 'VND', provider: 'SANDBOX', status: 'SUCCEEDED',
      checkoutUrl: null, failureReason: null, needsReview: true, createdAt: '2026-10-01T00:00:00Z', succeededAt: null, refund: null };
    backend.expectOne('/api/v1/admin/payments?page=0&size=20&needsReview=true')
      .flush({ ...emptyPage, content: [payment], totalElements: 1, totalPages: 1 });
    fixture.detectChanges();
    expect(el.textContent).toContain('Needs review');

    [...el.querySelectorAll('button')].find((b) => b.textContent?.includes('Refund'))!.click();
    const refund = backend.expectOne('/api/v1/payments/p1/refund');
    expect(refund.request.method).toBe('POST');
    refund.flush({ ...payment, status: 'REFUND_PENDING' });
    fixture.detectChanges();
    expect(el.textContent).toContain('Refund requested');
  });
});
