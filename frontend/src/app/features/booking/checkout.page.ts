import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { catchError, combineLatest, debounceTime, distinctUntilChanged, finalize, map, of, startWith, switchMap } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { BookingApi, newIdempotencyKey } from '../../core/bookings/booking-api';
import { BookingRequest, Quote } from '../../core/bookings/booking.models';
import { ApiError, toApiError } from '../../core/http/api-error';
import { FieldError } from '../../shared/forms/field-error';
import { applyServerFieldErrors, PHONE_PATTERN } from '../../shared/forms/form-errors';
import { VndPipe } from '../../shared/pipes/vnd.pipe';

type QuoteState = { status: 'loading' } | { status: 'ready'; quote: Quote } | { status: 'error'; error: ApiError };

/**
 * Checkout: shows the backend quote (never computes prices itself) and creates the booking. The idempotency key is
 * tied to the request content: retrying an unchanged form reuses it, any edit starts a new attempt.
 */
@Component({
  selector: 'app-checkout-page',
  imports: [ReactiveFormsModule, RouterLink, VndPipe, FieldError],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Complete your booking</h1>
    @if (!validLink()) {
      <div class="alert alert-error" role="alert">
        This booking link is incomplete. <a routerLink="/hotels">Start a new search</a>.
      </div>
    } @else {
      <div class="layout">
        <form class="card" [formGroup]="form" (ngSubmit)="submit()" novalidate>
          <h2>Guests</h2>
          <div class="row">
            <div class="field">
              <label for="adults">Adults</label>
              <input id="adults" type="number" min="1" max="40" formControlName="adults" />
              <app-field-error [control]="form.controls.adults" />
            </div>
            <div class="field">
              <label for="children">Children</label>
              <input id="children" type="number" min="0" max="20" formControlName="children" />
              <app-field-error [control]="form.controls.children" />
            </div>
          </div>
          <h2>Contact details</h2>
          <div class="field">
            <label for="contactName">Full name</label>
            <input id="contactName" formControlName="contactName" autocomplete="name" />
            <app-field-error [control]="form.controls.contactName" />
          </div>
          <div class="field">
            <label for="contactEmail">Email</label>
            <input id="contactEmail" type="email" formControlName="contactEmail" autocomplete="email" />
            <app-field-error [control]="form.controls.contactEmail" />
          </div>
          <div class="field">
            <label for="contactPhone">Phone (optional)</label>
            <input id="contactPhone" type="tel" formControlName="contactPhone" autocomplete="tel" />
            <app-field-error [control]="form.controls.contactPhone" />
          </div>
          <div class="field">
            <label for="specialRequests">Special requests (optional)</label>
            <textarea id="specialRequests" rows="3" formControlName="specialRequests"></textarea>
            <app-field-error [control]="form.controls.specialRequests" />
          </div>
          <label class="check">
            <input type="checkbox" formControlName="acceptPolicy" />
            I accept the cancellation policy: free cancellation until 48 hours before check-in.
          </label>
          <app-field-error [control]="form.controls.acceptPolicy" />

          @if (submitError(); as e) {
            <p class="alert alert-error" role="alert">
              {{ e }}
              @if (soldOut()) { <a [routerLink]="['/hotels', hotelId()]" [queryParams]="backParams()">Choose another room</a> }
            </p>
          }
          <button class="btn btn-primary btn-block" type="submit" [disabled]="submitting() || quoteState().status !== 'ready'">
            {{ submitting() ? 'Reserving…' : 'Reserve now' }}
          </button>
          <p class="muted small">Your rooms are held for 15 minutes after you reserve while you complete payment.</p>
        </form>

        <aside class="card summary" aria-live="polite">
          <h2>Price summary</h2>
          @switch (quoteState().status) {
            @case ('loading') { <p class="muted" role="status">Calculating price…</p> }
            @case ('error') { <p class="alert alert-error" role="alert">{{ quoteError() }}</p> }
            @case ('ready') {
              @if (quote(); as q) {
                <p><strong>{{ q.hotelName }}</strong></p>
                <p class="muted">{{ q.checkInDate }} → {{ q.checkOutDate }} · {{ q.nights }} {{ q.nights === 1 ? 'night' : 'nights' }}</p>
                @for (line of q.lines; track line.roomTypeId) {
                  <div class="line"><span>{{ line.quantity }} × {{ line.roomTypeName }} × {{ line.nights }} nights</span><span>{{ line.lineSubtotal | vnd }}</span></div>
                }
                <div class="line"><span>Service fee ({{ q.serviceFeePercent }}%)</span><span>{{ q.feeAmount | vnd }}</span></div>
                <div class="line"><span>VAT ({{ q.taxRatePercent }}%)</span><span>{{ q.taxAmount | vnd }}</span></div>
                <div class="line total"><span>Total</span><span>{{ q.totalAmount | vnd }}</span></div>
                @if (!q.available) {
                  <p class="alert alert-error" role="status">These rooms look fully booked for your dates. You can still try; the final check happens when you reserve.</p>
                }
              }
            }
          }
        </aside>
      </div>
    }
  `,
  styles: `
    .layout { display: grid; grid-template-columns: 1fr 340px; gap: 24px; align-items: start; }
    @media (max-width: 800px) { .layout { grid-template-columns: 1fr; } }
    h2 { font-size: 1.1rem; margin: 0 0 12px; }
    .row { display: flex; gap: 12px; }
    .row .field { flex: 1; }
    textarea { font: inherit; padding: 10px 12px; border: 1px solid var(--color-border); border-radius: var(--radius); }
    .check { display: flex; gap: 8px; align-items: flex-start; margin: 8px 0 16px; }
    .line { display: flex; justify-content: space-between; gap: 12px; padding: 4px 0; }
    .total { border-top: 1px solid var(--color-border); margin-top: 8px; padding-top: 8px; font-weight: 700; font-size: 1.1rem; }
    .small { font-size: 0.85rem; }
  `,
})
export class CheckoutPage {
  private readonly bookings = inject(BookingApi);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  /** Query params (withComponentInputBinding). */
  readonly hotelId = input<string | undefined>();
  readonly roomTypeId = input<string | undefined>();
  readonly quantity = input<string | undefined>();
  readonly checkIn = input<string | undefined>();
  readonly checkOut = input<string | undefined>();
  readonly guests = input<string | undefined>();

  protected readonly submitting = signal(false);
  protected readonly submitError = signal<string | null>(null);
  protected readonly soldOut = signal(false);
  private idempotencyKey = newIdempotencyKey();

  protected readonly validLink = computed(() => !!(this.hotelId() && this.roomTypeId() && this.checkIn() && this.checkOut()));
  protected readonly backParams = computed(() => ({ checkIn: this.checkIn(), checkOut: this.checkOut(), guests: this.guests() }));

  protected readonly form = inject(FormBuilder).nonNullable.group({
    adults: [2, [Validators.required, Validators.min(1), Validators.max(40)]],
    children: [0, [Validators.required, Validators.min(0), Validators.max(20)]],
    contactName: ['', [Validators.required, Validators.maxLength(150)]],
    contactEmail: ['', [Validators.required, Validators.email, Validators.maxLength(255)]],
    contactPhone: ['', [Validators.pattern(PHONE_PATTERN)]],
    specialRequests: ['', [Validators.maxLength(1000)]],
    acceptPolicy: [false, [Validators.requiredTrue]],
  });

  /** Re-quote (debounced, latest wins) when the link inputs arrive or the party size changes. */
  private readonly link = computed(() => [this.hotelId(), this.roomTypeId(), this.quantity(), this.checkIn(), this.checkOut()].join('|'));
  protected readonly quoteState = toSignal(
    combineLatest([
      toObservable(this.link),
      this.form.valueChanges.pipe(
        startWith(null),
        map(() => `${this.form.controls.adults.value}|${this.form.controls.children.value}`),
        distinctUntilChanged(),
      ),
    ]).pipe(
      debounceTime(300),
      switchMap(() => {
        if (!this.validLink() || this.form.controls.adults.invalid || this.form.controls.children.invalid) {
          return of<QuoteState>({ status: 'loading' });
        }
        return this.bookings.quote(this.buildRequest()).pipe(
          map((quote): QuoteState => ({ status: 'ready', quote })),
          catchError((err) => of<QuoteState>({ status: 'error', error: toApiError(err) })),
          startWith<QuoteState>({ status: 'loading' }),
        );
      }),
    ),
    { initialValue: { status: 'loading' } as QuoteState },
  );

  protected readonly quote = computed(() => {
    const s = this.quoteState();
    return s.status === 'ready' ? s.quote : null;
  });
  protected readonly quoteError = computed(() => {
    const s = this.quoteState();
    if (s.status !== 'error') return '';
    return s.error.code === 'CAPACITY_EXCEEDED'
      ? 'These rooms cannot host that many guests. Reduce the number of guests or choose more rooms.'
      : s.error.message;
  });

  constructor() {
    const user = this.auth.user();
    this.form.patchValue({ contactName: user?.fullName ?? '', contactEmail: user?.email ?? '', contactPhone: user?.phone ?? '' });
    effect(() => {
      const guests = Number(this.guests());
      if (Number.isInteger(guests) && guests >= 1 && guests <= 40) {
        this.form.controls.adults.setValue(guests);
      }
    });
    // Any edit means a new attempt, so it must not reuse the previous key.
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => (this.idempotencyKey = newIdempotencyKey()));
  }

  submit(): void {
    if (this.submitting()) return;
    this.form.markAllAsTouched();
    if (this.form.invalid || !this.validLink()) return;
    this.submitting.set(true);
    this.submitError.set(null);
    this.soldOut.set(false);
    this.bookings
      .create(this.buildRequest(), this.idempotencyKey)
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        next: (booking) => void this.router.navigate(['/bookings', booking.id], { replaceUrl: true }),
        error: (err) => {
          const e = toApiError(err);
          if (e.code === 'ROOM_SOLD_OUT') {
            this.soldOut.set(true);
            this.submitError.set('Sorry, these rooms were just booked by someone else.');
          } else if (!applyServerFieldErrors(this.form, e)) {
            // Network errors keep the same key, so pressing the button again cannot create a duplicate.
            this.submitError.set(e.message);
          }
        },
      });
  }

  private buildRequest(): BookingRequest {
    const v = this.form.getRawValue();
    return {
      hotelId: this.hotelId()!,
      checkInDate: this.checkIn()!,
      checkOutDate: this.checkOut()!,
      adults: v.adults,
      children: v.children,
      rooms: [{ roomTypeId: this.roomTypeId()!, quantity: Math.max(1, Number(this.quantity()) || 1) }],
      contactName: v.contactName.trim(),
      contactEmail: v.contactEmail.trim(),
      contactPhone: v.contactPhone.trim() || null,
      specialRequests: v.specialRequests.trim() || null,
    };
  }
}
