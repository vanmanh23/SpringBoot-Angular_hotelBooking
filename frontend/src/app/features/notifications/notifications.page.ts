import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { toApiError } from '../../core/http/api-error';
import { AppNotification, NotificationApi, UnreadNotifications } from '../../core/notifications/notification-api';

@Component({
  selector: 'app-notifications-page',
  imports: [RouterLink, DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="head">
      <h1>Notifications</h1>
      @if (unread.count() > 0) {
        <button class="btn btn-secondary" type="button" (click)="readAll()" [disabled]="busy()">Mark all as read</button>
      }
    </header>
    @if (error()) {
      <div class="alert alert-error" role="alert">{{ error() }}
        <button class="btn btn-secondary" type="button" (click)="load(0)">Try again</button>
      </div>
    }
    <ul class="list">
      @for (n of items(); track n.id) {
        <li class="card item" [class.unread]="!n.read">
          <div class="meta">
            <strong>{{ n.title }}</strong>
            <span class="muted">{{ n.createdAt | date: 'medium' }}</span>
          </div>
          <p class="body">{{ n.body }}</p>
          <div class="actions">
            @if (n.bookingId) { <a [routerLink]="['/bookings', n.bookingId]" (click)="markRead(n)">View booking</a> }
            @if (!n.read) { <button class="link" type="button" (click)="markRead(n)">Mark as read</button> }
          </div>
        </li>
      } @empty {
        @if (!loading()) { <li class="card muted">You have no notifications yet.</li> }
      }
    </ul>
    @if (loading()) { <p class="muted" role="status">Loading…</p> }
    @if (page() + 1 < totalPages() && !loading()) {
      <button class="btn btn-secondary" type="button" (click)="load(page() + 1)">Load more</button>
    }
  `,
  styles: `
    .head { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
    .list { list-style: none; padding: 0; display: flex; flex-direction: column; gap: 8px; }
    .item { padding: 16px; }
    .item.unread { border-left: 4px solid var(--color-primary); }
    .meta { display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
    .body { white-space: pre-line; overflow-wrap: anywhere; margin: 8px 0; }
    .actions { display: flex; gap: 16px; align-items: center; }
    .link { font: inherit; background: none; border: 0; color: var(--color-primary); cursor: pointer; padding: 0; text-decoration: underline; }
  `,
})
export class NotificationsPage implements OnInit {
  private readonly api = inject(NotificationApi);
  protected readonly unread = inject(UnreadNotifications);

  protected readonly items = signal<AppNotification[]>([]);
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly page = signal(0);
  protected readonly totalPages = signal(0);

  ngOnInit(): void {
    this.load(0);
  }

  load(page: number): void {
    this.loading.set(true);
    this.error.set(null);
    this.api
      .mine(page)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (p) => {
          this.page.set(p.page);
          this.totalPages.set(p.totalPages);
          this.items.update((list) => (page === 0 ? p.content : [...list, ...p.content]));
          if (page === 0) this.unread.refresh();
        },
        error: (err) => this.error.set(toApiError(err).message),
      });
  }

  markRead(n: AppNotification): void {
    if (n.read) return;
    this.api.markRead(n.id).subscribe({
      next: () => {
        this.items.update((list) => list.map((x) => (x.id === n.id ? { ...x, read: true } : x)));
        this.unread.set(this.unread.count() - 1);
      },
      error: () => undefined,
    });
  }

  readAll(): void {
    this.busy.set(true);
    this.api
      .markAllRead()
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: () => {
          this.items.update((list) => list.map((x) => ({ ...x, read: true })));
          this.unread.set(0);
        },
        error: (err) => this.error.set(toApiError(err).message),
      });
  }
}
