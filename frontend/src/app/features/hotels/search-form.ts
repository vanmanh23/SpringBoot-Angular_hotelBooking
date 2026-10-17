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
      <button class="btn btn-primary" type="submit">Search</button>
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
    .search-form { display: flex; flex-wrap: wrap; gap: 12px; align-items: flex-end; background: var(--color-surface);
      border: 1px solid var(--color-border); border-radius: var(--radius); padding: 16px; }
    .field { margin-bottom: 0; min-width: 140px; }
    .field.grow { flex: 1 1 220px; }
    .field.small { width: 90px; min-width: 90px; }
    .field-error { color: var(--color-danger); margin: 8px 0 0; }
    @media (max-width: 600px) { .field, .field.small { flex: 1 1 100%; width: auto; } .btn { width: 100%; } }
  `,
})
export class SearchForm {
  readonly initial = input<HotelSearchQuery>({});
  readonly search = output<HotelSearchQuery>();

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
    this.search.emit({
      city: v.city.trim() || undefined,
      checkIn: v.checkIn || undefined,
      checkOut: v.checkOut || undefined,
      guests: v.guests,
      rooms: v.rooms,
    });
  }
}
