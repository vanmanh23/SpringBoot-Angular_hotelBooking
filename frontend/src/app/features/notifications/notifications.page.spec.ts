import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { UnreadNotifications } from '../../core/notifications/notification-api';
import { signInAs } from '../../testing/sign-in';
import { NotificationsPage } from './notifications.page';

const item = (id: string, read: boolean) => ({
  id, type: 'BOOKING_CONFIRMED', title: 'Booking SH-1 confirmed', body: 'Hi <script>alert(1)</script>,\nconfirmed.',
  bookingId: 'b1', read, createdAt: '2026-10-01T10:00:00Z',
});

describe('notifications', () => {
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('the unread badge only polls while signed in', async () => {
    const unread = TestBed.inject(UnreadNotifications);
    TestBed.tick();
    backend.expectNone('/api/v1/notifications/me/unread-count');

    await signInAs(backend, ['CUSTOMER']);
    TestBed.tick();
    backend.expectOne('/api/v1/notifications/me/unread-count').flush({ count: 3 });
    expect(unread.count()).toBe(3);
  });

  it('lists notifications as plain text and marks them read', async () => {
    await signInAs(backend, ['CUSTOMER']);
    const unread = TestBed.inject(UnreadNotifications);
    TestBed.tick();
    backend.expectOne('/api/v1/notifications/me/unread-count').flush({ count: 2 });

    const fixture = TestBed.createComponent(NotificationsPage);
    fixture.detectChanges();
    backend.expectOne('/api/v1/notifications/me?page=0&size=20')
      .flush({ content: [item('n1', false), item('n2', false)], page: 0, size: 20, totalElements: 2, totalPages: 1 });
    backend.expectOne('/api/v1/notifications/me/unread-count').flush({ count: 2 });
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    expect(el.querySelector('script')).toBeNull();
    expect(el.textContent).toContain('<script>alert(1)</script>');
    expect(el.querySelectorAll('.unread').length).toBe(2);

    [...el.querySelectorAll('button')].find((b) => b.textContent?.includes('Mark as read'))!.click();
    backend.expectOne('/api/v1/notifications/n1/read').flush(null);
    fixture.detectChanges();
    expect(el.querySelectorAll('.unread').length).toBe(1);
    expect(unread.count()).toBe(1);

    [...el.querySelectorAll('button')].find((b) => b.textContent?.includes('Mark all as read'))!.click();
    backend.expectOne('/api/v1/notifications/me/read-all').flush(null);
    fixture.detectChanges();
    expect(el.querySelectorAll('.unread').length).toBe(0);
    expect(unread.count()).toBe(0);
  });
});
