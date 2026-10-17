import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, combineLatest, map, of, startWith, switchMap } from 'rxjs';
import { BookingApi } from '../../core/bookings/booking-api';
import { BookingStatus, BookingSummary, STATUS_LABELS } from '../../core/bookings/booking.models';
import { Page } from '../../core/http/page';
import { ApiError, toApiError } from '../../core/http/api-error';
import { VndPipe } from '../../shared/pipes/vnd.pipe';

type ListState = { status: 'loading' } | { status: 'loaded'; page: Page<BookingSummary> } | { status: 'error'; error: ApiError };

@Component({
  selector: 'app-my-bookings-page',
  imports: [RouterLink, VndPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>My bookings</h1>
    <div class="tabs" role="tablist" aria-label="Filter by status">
      @for (tab of tabs; track tab.value) {
        <button type="button" role="tab" [attr.aria-selected]="filter() === tab.value" [class.active]="filter() === tab.value"
                (click)="selectFilter(tab.value)">{{ tab.label }}</button>
      }
    </div>
    @switch (state().status) {
      @case ('loading') { <p class="muted" role="status">Loading bookings…</p> }
      @case ('error') {
        <div class="alert alert-error" role="alert">
          {{ errorMessage() }}
          <button class="btn btn-secondary" type="button" (click)="reload.set(reload() + 1)">Try again</button>
        </div>
      }
      @case ('loaded') {
        @for (b of items(); track b.id) {
          <a class="card item" [routerLink]="['/bookings', b.id]">
            <div>
              <strong>{{ b.hotelName }}</strong>
              <p class="muted">{{ b.checkInDate }} → {{ b.checkOutDate }} · {{ b.nights }} nights · {{ b.bookingCode }}</p>
            </div>
            <div class="right">
              <span class="badge" [attr.data-status]="b.status">{{ label(b.status) }}</span>
              <strong>{{ b.totalAmount | vnd }}</strong>
            </div>
          </a>
        } @empty {
          <div class="card empty">
            <p>No bookings here yet.</p>
            <a class="btn btn-primary" routerLink="/hotels">Find a hotel</a>
          </div>
        }
        @if (totalPages() > 1) {
          <nav class="pager" aria-label="Pagination">
            <button class="btn btn-secondary" type="button" [disabled]="page() === 0" (click)="page.set(page() - 1)">Previous</button>
            <span>Page {{ page() + 1 }} of {{ totalPages() }}</span>
            <button class="btn btn-secondary" type="button" [disabled]="page() + 1 >= totalPages()" (click)="page.set(page() + 1)">Next</button>
          </nav>
        }
      }
    }
  `,
  styles: `
    .tabs { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 16px; }
    .tabs button { font: inherit; padding: 6px 12px; border-radius: 999px; border: 1px solid var(--color-border); background: var(--color-surface); cursor: pointer; }
    .tabs button.active { background: var(--color-primary); color: var(--color-on-primary); border-color: var(--color-primary); }
    .item { display: flex; justify-content: space-between; gap: 12px; margin-bottom: 12px; color: inherit; text-decoration: none; flex-wrap: wrap; }
    .item p { margin: 4px 0 0; }
    .right { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; }
    .badge { padding: 2px 10px; border-radius: 999px; font-size: 0.85rem; background: var(--color-bg); border: 1px solid var(--color-border); }
    .badge[data-status='CONFIRMED'], .badge[data-status='COMPLETED'] { background: var(--color-success-bg); color: var(--color-success); }
    .badge[data-status='CANCELLED'], .badge[data-status='EXPIRED'] { background: var(--color-danger-bg); color: var(--color-danger); }
    .empty { text-align: center; }
    .pager { display: flex; justify-content: center; align-items: center; gap: 16px; margin-top: 16px; }
  `,
})
export class MyBookingsPage {
  private readonly api = inject(BookingApi);

  protected readonly tabs: { value: BookingStatus | null; label: string }[] = [
    { value: null, label: 'All' },
    { value: 'PENDING_PAYMENT', label: 'Awaiting payment' },
    { value: 'CONFIRMED', label: 'Confirmed' },
    { value: 'COMPLETED', label: 'Completed' },
    { value: 'CANCELLED', label: 'Cancelled' },
    { value: 'EXPIRED', label: 'Expired' },
  ];
  protected readonly filter = signal<BookingStatus | null>(null);
  protected readonly page = signal(0);
  protected readonly reload = signal(0);

  protected readonly state = toSignal(
    combineLatest([toObservable(this.filter), toObservable(this.page), toObservable(this.reload)]).pipe(
      switchMap(([status, page]) =>
        this.api.mine(status, page).pipe(
          map((p): ListState => ({ status: 'loaded', page: p })),
          catchError((err) => of<ListState>({ status: 'error', error: toApiError(err) })),
          startWith<ListState>({ status: 'loading' }),
        ),
      ),
    ),
    { initialValue: { status: 'loading' } as ListState },
  );

  protected readonly items = computed(() => {
    const s = this.state();
    return s.status === 'loaded' ? s.page.content : [];
  });
  protected readonly totalPages = computed(() => {
    const s = this.state();
    return s.status === 'loaded' ? s.page.totalPages : 0;
  });
  protected readonly errorMessage = computed(() => {
    const s = this.state();
    return s.status === 'error' ? s.error.message : '';
  });

  selectFilter(status: BookingStatus | null): void {
    this.filter.set(status);
    this.page.set(0);
  }

  label(status: BookingStatus): string {
    return STATUS_LABELS[status];
  }
}
