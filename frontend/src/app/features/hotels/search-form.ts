import { ChangeDetectionStrategy, Component, effect, inject, input, output } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { HotelSearchQuery } from '../../core/hotels/hotel.models';
import { isoDate, nightsBetween } from '../../shared/forms/date-utils';

export const MAX_NIGHTS = 30;

/** Same rules as the backend: both dates or none, checkOut after checkIn, not in the past, <= 30 nights. */
export function stayDatesValidator(group: AbstractControl): ValidationErrors | null {
  const checkIn: string = group.get('checkIn')?.value ?? '';
  const checkOut: string = group.get('checkOut')?.value ?? '';
  if (!checkIn && !checkOut) return null;
  if (!checkIn || !checkOut) return { datesIncomplete: true };
  if (checkIn < isoDate(new Date())) return { checkInPast: true };
  const nights = nightsBetween(checkIn, checkOut);
  if (nights < 1) return { checkOutBeforeCheckIn: true };
  if (nights > MAX_NIGHTS) return { stayTooLong: true };
  return null;
}

@Component({
  selector: 'app-search-form',
  imports: [ReactiveFormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form class="search-form" [formGroup]="form" (ngSubmit)="submit()" novalidate aria-label="Search hotels">
      <div class="field grow">
        <label for="sf-city">Destination</label>
        <input id="sf-city" formControlName="city" placeholder="City, e.g. Da Nang" autocomplete="off" />
      </div>
      <div class="field">
        <label for="sf-checkin">Check-in</label>
        <input id="sf-checkin" type="date" formControlName="checkIn" [min]="today" />
      </div>
      <div class="field">
        <label for="sf-checkout">Check-out</label>
        <input id="sf-checkout" type="date" formControlName="checkOut" [min]="form.controls.checkIn.value || today" />
      </div>
      <div class="field small">
        <label for="sf-guests">Guests</label>
        <input id="sf-guests" type="number" formControlName="guests" min="1" max="40" />
      </div>
      <div class="field small">
        <label for="sf-rooms">Rooms</label>
        <input id="sf-rooms" type="number" formControlName="rooms" min="1" max="10" />
      </div>
      <button class="btn btn-primary search-btn" type="submit">
        <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" focusable="false">
          <path d="M10.5 3a7.5 7.5 0 0 1 5.96 12.05l4.25 4.24-1.42 1.42-4.24-4.25A7.5 7.5 0 1 1 10.5 3zm0 2a5.5 5.5 0 1 0 0 11 5.5 5.5 0 0 0 0-11z" fill="currentColor"/>
        </svg>
        <span>Search</span>
      </button>
    </form>
    @if (form.touched && form.errors; as errors) {
      <p class="field-error" role="alert">
        @if (errors['datesIncomplete']) { Choose both check-in and check-out dates. }
        @if (errors['checkInPast']) { Check-in cannot be in the past. }
        @if (errors['checkOutBeforeCheckIn']) { Check-out must be after check-in. }
        @if (errors['stayTooLong']) { Stays are limited to {{ maxNights }} nights. }
      </p>
    }
    @if (form.touched && (form.controls.guests.invalid || form.controls.rooms.invalid)) {
      <p class="field-error" role="alert">Guests must be 1–40 and rooms 1–10.</p>
    }
  `,
  styles: `
    .search-form { display: flex; align-items: center; background: var(--color-surface); border: 1px solid var(--color-border);
      border-radius: var(--radius-pill); box-shadow: var(--shadow); padding: 6px 6px 6px 8px; max-width: 980px; margin: 0 auto; text-align: left; }
    .field { margin: 0; padding: 8px 18px; min-width: 0; flex: 1 1 130px; position: relative; border-radius: var(--radius-pill); }
    .field + .field::before { content: ''; position: absolute; left: 0; top: 22%; height: 56%; border-left: 1px solid var(--color-border); }
    .field:hover { background: var(--color-surface-alt); }
    .field.grow { flex: 2 1 220px; }
    .field.small { flex: 0 1 96px; }
    .field label { font-size: 0.75rem; font-weight: 700; margin-bottom: 2px; }
    .field input { border: 0; padding: 0; background: transparent; font-size: 0.95rem; min-width: 0; width: 100%; }
    .field input:hover, .field input:focus-visible { border: 0; box-shadow: none; }
    .field:focus-within { background: var(--color-surface); box-shadow: var(--shadow); }
    .search-btn { border-radius: var(--radius-pill); padding: 14px 22px; margin-left: 6px; flex: none; }
    .field-error { color: var(--color-danger); margin: 12px auto 0; max-width: 980px; text-align: center; }
    @media (max-width: 760px) {
      .search-form { flex-direction: column; align-items: stretch; border-radius: var(--radius); padding: 8px; }
      .field, .field.grow, .field.small { flex: none; border-radius: var(--radius-sm); padding: 10px 12px; }
      .field + .field::before { left: 12px; right: 12px; top: 0; height: 0; border-left: 0; border-top: 1px solid var(--color-border); }
      .search-btn { margin: 8px 0 0; }
    }
  `,
})
export class SearchForm {
  readonly initial = input<HotelSearchQuery>({});
  readonly searched = output<HotelSearchQuery>();

  protected readonly today = isoDate(new Date());
  protected readonly maxNights = MAX_NIGHTS;

  protected readonly form = inject(FormBuilder).nonNullable.group(
    {
      city: ['', [Validators.maxLength(100)]],
      checkIn: [''],
      checkOut: [''],
      guests: [2, [Validators.required, Validators.min(1), Validators.max(40)]],
      rooms: [1, [Validators.required, Validators.min(1), Validators.max(10)]],
    },
    { validators: stayDatesValidator },
  );

  constructor() {
    effect(() => {
      const q = this.initial();
      this.form.reset({
        city: q.city ?? '',
        checkIn: q.checkIn ?? '',
        checkOut: q.checkOut ?? '',
        guests: q.guests ?? 2,
        rooms: q.rooms ?? 1,
      });
    });
  }

  submit(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid) return;
    const v = this.form.getRawValue();
    this.searched.emit({
      city: v.city.trim() || undefined,
      checkIn: v.checkIn || undefined,
      checkOut: v.checkOut || undefined,
      guests: v.guests,
      rooms: v.rooms,
    });
  }
}
