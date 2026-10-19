import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { BookingStatus, STATUS_LABELS } from '../../core/bookings/booking.models';
import { toApiError } from '../../core/http/api-error';
import { LoadError } from '../../shared/ui/load-error';
import { AdminApi, ManagedBooking } from '../../core/manage/manage-api';
import { Payment, PaymentStatus } from '../../core/payments/payment-api';
import { VndPipe } from '../../shared/pipes/vnd.pipe';

/** All bookings, searchable by code (FR-ADMIN-03). */
@Component({
  selector: 'app-admin-bookings',
  imports: [RouterLink, VndPipe, LoadError],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toolbar">
      <div class="field"><label for="ab-code">Booking code</label><input id="ab-code" #code maxlength="30" (keydown.enter)="apply(code.value, st.value)" /></div>
      <div class="field"><label for="ab-status">Status</label>
        <select id="ab-status" #st><option value="">All</option>
          @for (s of statuses; track s) { <option [value]="s">{{ labels[s] }}</option> }</select></div>
      <button class="btn btn-secondary" type="button" (click)="apply(code.value, st.value)">Search</button>
    </div>
    <app-load-error [message]="error()" (retry)="load(page())" />
    <div class="table-wrap">
      <table class="table">
        <thead><tr><th>Code</th><th>Hotel</th><th>Guest</th><th>Stay</th><th>Status</th><th>Total</th></tr></thead>
        <tbody>
          @for (b of bookings(); track b.id) {
            <tr>
              <td><a [routerLink]="['/bookings', b.id]">{{ b.bookingCode }}</a></td>
              <td>{{ b.hotelName }}</td><td>{{ b.contactName }}</td>
              <td>{{ b.checkInDate }} → {{ b.checkOutDate }}</td>
              <td><span class="status-pill">{{ labels[b.status] }}</span>{{ b.refundStatus !== 'NONE' ? ' · refund ' + b.refundStatus : '' }}</td>
              <td>{{ b.totalAmount | vnd }}</td>
            </tr>
          } @empty { <tr><td colspan="6" class="muted">{{ loading() ? 'Loading…' : 'No bookings match.' }}</td></tr> }
        </tbody>
      </table>
    </div>
    @if (totalPages() > 1) {
      <nav class="pager" aria-label="Pages">
        <button class="btn btn-secondary btn-small" type="button" [disabled]="page() === 0" (click)="load(page() - 1)">Previous</button>
        <span>Page {{ page() + 1 }} / {{ totalPages() }}</span>
        <button class="btn btn-secondary btn-small" type="button" [disabled]="page() + 1 >= totalPages()" (click)="load(page() + 1)">Next</button>
      </nav>
    }
  `,
  styles: `.pager { display: flex; gap: 12px; align-items: center; margin-top: 12px; }`,
})
export class AdminBookings implements OnInit {
  private readonly api = inject(AdminApi);
  protected readonly statuses: BookingStatus[] = ['PENDING_PAYMENT', 'CONFIRMED', 'COMPLETED', 'CANCELLED', 'EXPIRED'];
  protected readonly labels = STATUS_LABELS;
  protected readonly bookings = signal<ManagedBooking[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly page = signal(0);
  protected readonly totalPages = signal(0);
  private filter: { code: string | null; status: BookingStatus | null } = { code: null, status: null };

  ngOnInit(): void {
    this.load(0);
  }

  apply(code: string, status: string): void {
    this.filter = { code: code.trim() || null, status: (status || null) as BookingStatus | null };
    this.load(0);
  }

  load(page: number): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.bookings(this.filter, page).pipe(finalize(() => this.loading.set(false))).subscribe({
      next: (p) => {
        this.bookings.set(p.content);
        this.page.set(p.page);
        this.totalPages.set(p.totalPages);
      },
      error: (err) => this.error.set(toApiError(err).message),
    });
  }
}

/** Payment monitoring with the "needs review" queue (FR-ADMIN-04) and manual refunds. */
@Component({
  selector: 'app-admin-payments',
  imports: [DatePipe, RouterLink, VndPipe, LoadError],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toolbar">
      <div class="field"><label for="ap-status">Status</label>
        <select id="ap-status" #st><option value="">All</option>
          @for (s of statuses; track s) { <option [value]="s">{{ s }}</option> }</select></div>
      <label class="check"><input type="checkbox" #review [checked]="needsReview" /> Needs review only</label>
      <button class="btn btn-secondary" type="button" (click)="apply(st.value, review.checked)">Filter</button>
    </div>
    <app-load-error [message]="error()" (retry)="load(page())" />
    @if (message()) { <p class="alert alert-success" role="status">{{ message() }}</p> }
    <div class="table-wrap">
      <table class="table">
        <thead><tr><th>Created</th><th>Booking</th><th>Amount</th><th>Status</th><th>Flags</th><th></th></tr></thead>
        <tbody>
          @for (p of payments(); track p.id) {
            <tr>
              <td>{{ p.createdAt | date: 'short' }}</td>
              <td><a [routerLink]="['/bookings', p.bookingId]">{{ p.bookingId.slice(0, 8) }}…</a></td>
              <td>{{ p.amount | vnd }}</td>
              <td><span class="status-pill">{{ p.status }}</span>{{ p.refund ? ' · refund ' + p.refund.status : '' }}</td>
              <td>@if (p.needsReview) { <strong class="flag">Needs review</strong> } {{ p.failureReason ?? '' }}</td>
              <td>
                @if (p.status === 'SUCCEEDED') {
                  <button class="btn btn-secondary btn-small" type="button" [disabled]="busy() === p.id" (click)="refund(p)">Refund</button>
                }
              </td>
            </tr>
          } @empty { <tr><td colspan="6" class="muted">{{ loading() ? 'Loading…' : 'No payments match.' }}</td></tr> }
        </tbody>
      </table>
    </div>
    @if (totalPages() > 1) {
      <nav class="pager" aria-label="Pages">
        <button class="btn btn-secondary btn-small" type="button" [disabled]="page() === 0" (click)="load(page() - 1)">Previous</button>
        <span>Page {{ page() + 1 }} / {{ totalPages() }}</span>
        <button class="btn btn-secondary btn-small" type="button" [disabled]="page() + 1 >= totalPages()" (click)="load(page() + 1)">Next</button>
      </nav>
    }
  `,
  styles: `
    .check { display: flex; gap: 6px; align-items: center; padding-bottom: 10px; }
    .flag { color: var(--color-danger); }
    .pager { display: flex; gap: 12px; align-items: center; margin-top: 12px; }
  `,
})
export class AdminPayments implements OnInit {
  private readonly api = inject(AdminApi);
  protected readonly statuses: PaymentStatus[] = ['PENDING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'REFUND_PENDING', 'REFUNDED'];
  protected readonly payments = signal<Payment[]>([]);
  protected readonly loading = signal(false);
  protected readonly busy = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly message = signal<string | null>(null);
  protected readonly page = signal(0);
  protected readonly totalPages = signal(0);
  protected needsReview = false;
  private status: PaymentStatus | null = null;

  ngOnInit(): void {
    this.load(0);
  }

  apply(status: string, needsReview: boolean): void {
    this.status = (status || null) as PaymentStatus | null;
    this.needsReview = needsReview;
    this.load(0);
  }

  load(page: number): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.payments({ status: this.status, needsReview: this.needsReview || null }, page)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (p) => {
          this.payments.set(p.content);
          this.page.set(p.page);
          this.totalPages.set(p.totalPages);
        },
        error: (err) => this.error.set(toApiError(err).message),
      });
  }

  refund(p: Payment): void {
    this.busy.set(p.id);
    this.message.set(null);
    this.api.refund(p.id, 'Manual refund by administrator').pipe(finalize(() => this.busy.set(null))).subscribe({
      next: (updated) => {
        this.payments.update((list) => list.map((x) => (x.id === updated.id ? updated : x)));
        this.message.set('Refund requested. Its status updates when the provider confirms it.');
      },
      error: (err) => this.error.set(toApiError(err).message),
    });
  }
}
