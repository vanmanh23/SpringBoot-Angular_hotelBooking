import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, input, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { filter, finalize, interval, map, switchMap } from 'rxjs';
import { BookingApi } from '../../core/bookings/booking-api';
import { Booking, STATUS_LABELS } from '../../core/bookings/booking.models';
import { toApiError } from '../../core/http/api-error';
import { VndPipe } from '../../shared/pipes/vnd.pipe';

@Component({
  selector: 'app-booking-detail-page',
  imports: [RouterLink, VndPipe, DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a routerLink="/my-bookings" class="back">← My bookings</a>
    @if (loading()) {
      <p class="muted" role="status">Loading booking…</p>
    } @else if (loadError()) {
      <div class="alert alert-error" role="alert">
        {{ loadError() }}
        <button class="btn btn-secondary" type="button" (click)="load()">Try again</button>
      </div>
    } @else if (booking(); as b) {
      <header class="head">
        <div>
          <h1>{{ b.hotelName }}</h1>
          <p class="muted">Booking code <strong>{{ b.bookingCode }}</strong></p>
        </div>
        <span class="badge" [attr.data-status]="b.status">{{ statusLabel() }}</span>
      </header>

      @if (b.status === 'PENDING_PAYMENT') {
        <div class="alert alert-pending" role="status">
          @if (secondsLeft() > 0) {
            Your rooms are held for <strong>{{ countdown() }}</strong>. Online payment opens in the next release;
            the hold is released automatically if it is not paid in time.
          } @else {
            The hold time has ended. Updating status…
          }
        </div>
        <button class="btn btn-primary" type="button" disabled title="Online payment arrives in the next milestone">Pay now (coming soon)</button>
      }

      <div class="grid">
        <section class="card">
          <h2>Stay</h2>
          <p>{{ b.checkInDate }} → {{ b.checkOutDate }} · {{ b.nights }} {{ b.nights === 1 ? 'night' : 'nights' }}</p>
          <p>Check-in from {{ b.checkInTime }} (hotel time, {{ b.hotelTimeZone }})</p>
          <p>{{ b.adults }} adults{{ b.children ? ', ' + b.children + ' children' : '' }}</p>
          <h3>Contact</h3>
          <p>{{ b.contactName }} · {{ b.contactEmail }}{{ b.contactPhone ? ' · ' + b.contactPhone : '' }}</p>
          @if (b.specialRequests) { <p class="muted">“{{ b.specialRequests }}”</p> }
        </section>

        <section class="card">
          <h2>Price</h2>
          @for (line of b.lines; track line.roomTypeId) {
            <div class="line"><span>{{ line.quantity }} × {{ line.roomTypeName }} × {{ line.nights }} nights</span><span>{{ line.lineSubtotal | vnd }}</span></div>
          }
          <div class="line"><span>Service fee ({{ b.serviceFeePercent }}%)</span><span>{{ b.feeAmount | vnd }}</span></div>
          <div class="line"><span>VAT ({{ b.taxRatePercent }}%)</span><span>{{ b.taxAmount | vnd }}</span></div>
          <div class="line total"><span>Total</span><span>{{ b.totalAmount | vnd }}</span></div>
        </section>
      </div>

      <section class="card">
        <h2>Cancellation</h2>
        @if (b.status === 'CANCELLED') {
          <p>Cancelled on {{ b.cancelledAt | date: 'medium' }}{{ b.cancelReason ? ' — ' + b.cancelReason : '' }}.</p>
          @if (b.refundEligible === true) { <p>This cancellation qualifies for a refund, which is processed separately.</p> }
          @if (b.refundEligible === false) { <p>This cancellation was made after the free-cancellation deadline and is not refundable.</p> }
        } @else if (canCancel()) {
          <p>Free cancellation until <strong>{{ b.freeCancellationUntil | date: 'medium' }}</strong>.</p>
          @if (!confirming()) {
            <button class="btn btn-secondary" type="button" (click)="confirming.set(true)">Cancel booking</button>
          } @else {
            <div class="confirm" role="group" aria-label="Confirm cancellation">
              <label for="reason">Reason (optional)</label>
              <input id="reason" #reason maxlength="300" />
              <div class="actions">
                <button class="btn btn-primary danger" type="button" [disabled]="cancelling()" (click)="cancel(reason.value)">
                  {{ cancelling() ? 'Cancelling…' : 'Yes, cancel booking' }}
                </button>
                <button class="btn btn-secondary" type="button" [disabled]="cancelling()" (click)="confirming.set(false)">Keep booking</button>
              </div>
            </div>
          }
        } @else {
          <p class="muted">This booking can no longer be cancelled online.</p>
        }
        @if (cancelError()) { <p class="alert alert-error" role="alert">{{ cancelError() }}</p> }
      </section>

      <section class="card">
        <h2>History</h2>
        <ol class="history">
          @for (h of b.history; track $index) {
            <li><strong>{{ label(h.toStatus) }}</strong> · {{ h.at | date: 'medium' }}{{ h.reason ? ' — ' + h.reason : '' }}</li>
          }
        </ol>
      </section>
    }
  `,
  styles: `
    .back { display: inline-block; margin-bottom: 12px; }
    .head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
    .head h1 { margin: 0; }
    .badge { padding: 4px 12px; border-radius: 999px; font-weight: 600; background: var(--color-bg); border: 1px solid var(--color-border); }
    .badge[data-status='CONFIRMED'], .badge[data-status='COMPLETED'] { background: var(--color-success-bg); color: var(--color-success); }
    .badge[data-status='CANCELLED'], .badge[data-status='EXPIRED'] { background: var(--color-danger-bg); color: var(--color-danger); }
    .alert-pending { background: #fffaeb; color: #93370d; }
    .grid { display: grid; gap: 16px; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); margin: 16px 0; }
    .card { margin-bottom: 16px; }
    h2 { margin-top: 0; font-size: 1.1rem; }
    .line { display: flex; justify-content: space-between; gap: 12px; padding: 4px 0; }
    .total { border-top: 1px solid var(--color-border); margin-top: 8px; padding-top: 8px; font-weight: 700; }
    .confirm { display: flex; flex-direction: column; gap: 8px; max-width: 420px; }
    .confirm input { font: inherit; padding: 8px; border: 1px solid var(--color-border); border-radius: var(--radius); }
    .actions { display: flex; gap: 8px; flex-wrap: wrap; }
    .danger { background: var(--color-danger); }
    .history { padding-left: 20px; margin: 0; }
  `,
})
export class BookingDetailPage implements OnInit {
  private readonly api = inject(BookingApi);
  private readonly destroyRef = inject(DestroyRef);

  readonly id = input.required<string>();

  protected readonly booking = signal<Booking | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly confirming = signal(false);
  protected readonly cancelling = signal(false);
  protected readonly cancelError = signal<string | null>(null);

  private readonly now = toSignal(interval(1000).pipe(map(() => Date.now())), { initialValue: Date.now() });

  protected readonly statusLabel = computed(() => {
    const b = this.booking();
    return b ? STATUS_LABELS[b.status] : '';
  });
  protected readonly secondsLeft = computed(() => {
    const expires = this.booking()?.expiresAt;
    return expires ? Math.max(0, Math.floor((Date.parse(expires) - this.now()) / 1000)) : 0;
  });
  protected readonly countdown = computed(() => {
    const s = this.secondsLeft();
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
  });
  /** UI hint only; the backend enforces the real rule (check-in moment, state machine). */
  protected readonly canCancel = computed(() => {
    const b = this.booking();
    return !!b && (b.status === 'PENDING_PAYMENT' || b.status === 'CONFIRMED');
  });

  ngOnInit(): void {
    this.load();
    // While a hold is pending, poll the authoritative status so expiry/confirmation show up without a reload.
    interval(10_000)
      .pipe(
        filter(() => this.booking()?.status === 'PENDING_PAYMENT'),
        switchMap(() => this.api.status(this.id())),
        filter((s) => s.status !== this.booking()?.status),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => this.load());
  }

  load(): void {
    this.loadError.set(null);
    this.api
      .get(this.id())
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (b) => this.booking.set(b),
        error: (err) => {
          const e = toApiError(err);
          this.loadError.set(e.status === 404 ? 'Booking not found.' : e.message);
        },
      });
  }

  cancel(reason: string): void {
    if (this.cancelling()) return;
    this.cancelling.set(true);
    this.cancelError.set(null);
    this.api
      .cancel(this.id(), reason.trim() || null)
      .pipe(finalize(() => this.cancelling.set(false)))
      .subscribe({
        next: (b) => {
          this.booking.set(b);
          this.confirming.set(false);
        },
        error: (err) => {
          const e = toApiError(err);
          this.cancelError.set(e.message);
          if (e.code === 'INVALID_STATUS_TRANSITION') this.load(); // status changed meanwhile: show the truth
        },
      });
  }

  label(status: Booking['status']): string {
    return STATUS_LABELS[status];
  }
}
