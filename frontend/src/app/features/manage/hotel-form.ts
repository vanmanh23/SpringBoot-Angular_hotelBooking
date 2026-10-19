import { ChangeDetectionStrategy, Component, effect, input, output } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ApiError } from '../../core/http/api-error';
import { HotelRequest, ManagedHotel } from '../../core/manage/manage-api';
import { FieldError } from '../../shared/forms/field-error';
import { applyServerFieldErrors } from '../../shared/forms/form-errors';

/** Create/edit form for hotel details. Mirrors the backend HotelRequest validation (UX only). */
@Component({
  selector: 'app-hotel-form',
  imports: [ReactiveFormsModule, FieldError],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form [formGroup]="form" (ngSubmit)="submit()" novalidate class="grid">
      <div class="field wide"><label for="hf-name">Name</label>
        <input id="hf-name" formControlName="name" maxlength="200" /><app-field-error [control]="form.controls.name" /></div>
      <div class="field wide"><label for="hf-address">Address</label>
        <input id="hf-address" formControlName="address" maxlength="500" /><app-field-error [control]="form.controls.address" /></div>
      <div class="field"><label for="hf-city">City</label>
        <input id="hf-city" formControlName="city" maxlength="100" /><app-field-error [control]="form.controls.city" /></div>
      <div class="field"><label for="hf-country">Country code</label>
        <input id="hf-country" formControlName="countryCode" maxlength="2" /><app-field-error [control]="form.controls.countryCode" /></div>
      <div class="field"><label for="hf-stars">Stars</label>
        <select id="hf-stars" formControlName="starRating">
          <option [ngValue]="null">—</option>
          @for (n of [1, 2, 3, 4, 5]; track n) { <option [ngValue]="n">{{ n }}</option> }
        </select></div>
      <div class="field"><label for="hf-tz">Time zone</label>
        <input id="hf-tz" formControlName="timeZone" placeholder="Asia/Ho_Chi_Minh" /><app-field-error [control]="form.controls.timeZone" /></div>
      <div class="field"><label for="hf-in">Check-in time</label>
        <input id="hf-in" type="time" formControlName="checkInTime" /></div>
      <div class="field"><label for="hf-out">Check-out time</label>
        <input id="hf-out" type="time" formControlName="checkOutTime" /></div>
      <div class="field"><label for="hf-tax">VAT %</label>
        <input id="hf-tax" type="number" min="0" max="30" step="0.01" formControlName="taxRatePercent" />
        <app-field-error [control]="form.controls.taxRatePercent" /></div>
      <div class="field"><label for="hf-fee">Service fee %</label>
        <input id="hf-fee" type="number" min="0" max="30" step="0.01" formControlName="serviceFeePercent" />
        <app-field-error [control]="form.controls.serviceFeePercent" /></div>
      <div class="field wide"><label for="hf-desc">Description</label>
        <textarea id="hf-desc" formControlName="description" rows="4" maxlength="5000"></textarea></div>
      <div class="field wide"><label for="hf-policy">Cancellation policy</label>
        <textarea id="hf-policy" formControlName="cancellationPolicy" rows="2" maxlength="2000"></textarea></div>
      <div class="wide">
        <button class="btn btn-primary" type="submit" [disabled]="saving()">{{ saving() ? 'Saving…' : submitLabel() }}</button>
      </div>
    </form>
  `,
  styles: `
    .grid { display: grid; gap: 0 16px; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); }
    .wide { grid-column: 1 / -1; }
  `,
})
export class HotelForm {
  readonly initial = input<ManagedHotel | null>(null);
  readonly saving = input(false);
  readonly submitLabel = input('Save');
  /** Set by the parent after a 400 so backend field errors show next to the inputs. */
  readonly serverError = input<ApiError | null>(null);
  readonly save = output<HotelRequest>();

  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(200)] }),
    address: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(500)] }),
    city: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(100)] }),
    countryCode: new FormControl('VN', { nonNullable: true, validators: [Validators.required, Validators.pattern(/^[A-Z]{2}$/)] }),
    starRating: new FormControl<number | null>(null),
    timeZone: new FormControl('Asia/Ho_Chi_Minh', { nonNullable: true }),
    checkInTime: new FormControl('14:00', { nonNullable: true }),
    checkOutTime: new FormControl('12:00', { nonNullable: true }),
    taxRatePercent: new FormControl<number | null>(8, [Validators.min(0), Validators.max(30)]),
    serviceFeePercent: new FormControl<number | null>(5, [Validators.min(0), Validators.max(30)]),
    description: new FormControl('', { nonNullable: true }),
    cancellationPolicy: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    effect(() => {
      const h = this.initial();
      if (h) {
        this.form.reset({
          name: h.name, address: h.address, city: h.city, countryCode: h.countryCode, starRating: h.starRating,
          timeZone: h.timeZone, checkInTime: h.checkInTime, checkOutTime: h.checkOutTime,
          taxRatePercent: h.taxRatePercent, serviceFeePercent: h.serviceFeePercent,
          description: h.description ?? '', cancellationPolicy: h.cancellationPolicy ?? '',
        });
      }
    });
    effect(() => {
      const e = this.serverError();
      if (e) applyServerFieldErrors(this.form, e);
    });
  }

  submit(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid) return;
    const v = this.form.getRawValue();
    const blank = (s: string) => (s.trim() ? s.trim() : null);
    this.save.emit({
      name: v.name.trim(), address: v.address.trim(), city: v.city.trim(), countryCode: v.countryCode.trim().toUpperCase(),
      starRating: v.starRating, timeZone: blank(v.timeZone), checkInTime: blank(v.checkInTime),
      checkOutTime: blank(v.checkOutTime), taxRatePercent: v.taxRatePercent, serviceFeePercent: v.serviceFeePercent,
      description: blank(v.description), cancellationPolicy: blank(v.cancellationPolicy),
      version: this.initial()?.version,
    });
  }
}
