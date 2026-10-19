import { HttpClient, HttpParams } from '@angular/common/http';
import { DestroyRef, effect, inject, Injectable, signal } from '@angular/core';
import { catchError, EMPTY, interval, Observable, Subscription, switchMap, tap } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { API_BASE_URL } from '../config/api.config';
import { Page } from '../http/page';

export interface AppNotification {
  id: string;
  type: string;
  title: string;
  /** Plain text; rendered with interpolation only (never innerHTML). */
  body: string;
  bookingId: string | null;
  read: boolean;
  createdAt: string;
}

@Injectable({ providedIn: 'root' })
export class NotificationApi {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);

  mine(page: number, size = 20): Observable<Page<AppNotification>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<Page<AppNotification>>(`${this.base}/notifications/me`, { params });
  }

  unreadCount(): Observable<{ count: number }> {
    return this.http.get<{ count: number }>(`${this.base}/notifications/me/unread-count`);
  }

  markRead(id: string): Observable<void> {
    return this.http.post<void>(`${this.base}/notifications/${encodeURIComponent(id)}/read`, null);
  }

  markAllRead(): Observable<void> {
    return this.http.post<void>(`${this.base}/notifications/me/read-all`, null);
  }
}

/** Unread badge for the header: refreshed on sign-in and every minute while signed in. */
@Injectable({ providedIn: 'root' })
export class UnreadNotifications {
  static readonly POLL_MS = 60_000;

  private readonly api = inject(NotificationApi);
  private readonly auth = inject(AuthService);
  private poll: Subscription | null = null;

  readonly count = signal(0);

  constructor() {
    effect(() => {
      const signedIn = this.auth.isAuthenticated();
      this.poll?.unsubscribe();
      this.poll = null;
      if (!signedIn) {
        this.count.set(0);
        return;
      }
      this.refresh();
      this.poll = interval(UnreadNotifications.POLL_MS)
        .pipe(switchMap(() => this.fetch()))
        .subscribe();
    });
    inject(DestroyRef).onDestroy(() => this.poll?.unsubscribe());
  }

  refresh(): void {
    this.fetch().subscribe();
  }

  set(count: number): void {
    this.count.set(Math.max(0, count));
  }

  private fetch(): Observable<unknown> {
    return this.api.unreadCount().pipe(
      tap((r) => this.count.set(r.count)),
      catchError(() => EMPTY), // the badge is cosmetic: never surface an error for it
    );
  }
}
