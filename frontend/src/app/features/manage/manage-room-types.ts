import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { RoomType } from '../../core/hotels/hotel.models';
import { toApiError } from '../../core/http/api-error';
import { ManagedHotel, ManageApi, RoomTypeRequest } from '../../core/manage/manage-api';
import { FieldError } from '../../shared/forms/field-error';
import { applyServerFieldErrors } from '../../shared/forms/form-errors';
import { VndPipe } from '../../shared/pipes/vnd.pipe';

type EditableRoomType = RoomType & { version: number };

@Component({
  selector: 'app-manage-room-types',
  imports: [ReactiveFormsModule, FieldError, VndPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="card">
      <h2>Room types</h2>
      <div class="table-wrap">
        <table class="table">
          <thead><tr><th>Name</th><th>Guests</th><th>Units</th><th>Base price / night</th><th>Status</th><th></th></tr></thead>
          <tbody>
            @for (r of hotel().roomTypes; track r.id) {
              <tr>
                <td>{{ r.name }}</td><td>{{ r.capacity }}</td><td>{{ r.totalUnits }}</td><td>{{ r.basePrice | vnd }}</td>
                <td><span class="status-pill">{{ r.status }}</span></td>
                <td class="row-actions">
                  <button class="btn btn-secondary btn-small" type="button" (click)="edit(r)">Edit</button>
                  <button class="btn btn-secondary btn-small" type="button" [disabled]="busy()" (click)="toggle(r)">
                    {{ r.status === 'ACTIVE' ? 'Deactivate' : 'Activate' }}
                  </button>
                </td>
              </tr>
            } @empty { <tr><td colspan="6" class="muted">No room types yet. Add one below.</td></tr> }
          </tbody>
        </table>
      </div>
    </section>

    <section class="card">
      <h2>{{ editing() ? 'Edit ' + editing()!.name : 'Add a room type' }}</h2>
      <form [formGroup]="form" (ngSubmit)="save()" novalidate class="grid">
        <div class="field"><label for="rt-name">Name</label><input id="rt-name" formControlName="name" maxlength="150" />
          <app-field-error [control]="form.controls.name" /></div>
        <div class="field"><label for="rt-capacity">Max guests</label><input id="rt-capacity" type="number" min="1" max="20" formControlName="capacity" />
          <app-field-error [control]="form.controls.capacity" /></div>
        <div class="field"><label for="rt-units">Units (rooms of this type)</label><input id="rt-units" type="number" min="0" max="1000" formControlName="totalUnits" />
          <app-field-error [control]="form.controls.totalUnits" /></div>
        <div class="field"><label for="rt-price">Base price per night (VND)</label><input id="rt-price" type="number" min="1000" step="1000" formControlName="basePrice" />
          <app-field-error [control]="form.controls.basePrice" /></div>
        <div class="field"><label for="rt-bed">Beds</label><input id="rt-bed" formControlName="bedDescription" maxlength="200" /></div>
        <div class="field wide"><label for="rt-desc">Description</label><textarea id="rt-desc" formControlName="description" rows="2" maxlength="2000"></textarea></div>
        <div class="wide actions">
          <button class="btn btn-primary" type="submit" [disabled]="busy()">{{ editing() ? 'Save room type' : 'Add room type' }}</button>
          @if (editing()) { <button class="btn btn-secondary" type="button" (click)="cancel()">Cancel</button> }
        </div>
      </form>
      @if (error()) { <p class="alert alert-error" role="alert">{{ error() }}</p> }
    </section>
  `,
  styles: `
    .card { margin-bottom: 16px; }
    h2 { margin-top: 0; font-size: 1.1rem; }
    .grid { display: grid; gap: 0 16px; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); }
    .wide { grid-column: 1 / -1; }
    .actions, .row-actions { display: flex; gap: 8px; flex-wrap: wrap; }
  `,
})
export class ManageRoomTypes {
  private readonly api = inject(ManageApi);

  readonly hotel = input.required<ManagedHotel>();
  readonly changed = output<void>();

  protected readonly editing = signal<EditableRoomType | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = new FormGroup({
    name: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.maxLength(150)] }),
    capacity: new FormControl<number | null>(2, [Validators.required, Validators.min(1), Validators.max(20)]),
    totalUnits: new FormControl<number | null>(1, [Validators.required, Validators.min(0), Validators.max(1000)]),
    basePrice: new FormControl<number | null>(null, [Validators.required, Validators.min(1000), Validators.max(1_000_000_000)]),
    bedDescription: new FormControl('', { nonNullable: true }),
    description: new FormControl('', { nonNullable: true }),
  });

  edit(r: EditableRoomType): void {
    this.editing.set(r);
    this.error.set(null);
    this.form.reset({ name: r.name, capacity: r.capacity, totalUnits: r.totalUnits, basePrice: r.basePrice,
      bedDescription: r.bedDescription ?? '', description: r.description ?? '' });
  }

  cancel(): void {
    this.editing.set(null);
    this.form.reset({ name: '', capacity: 2, totalUnits: 1, basePrice: null, bedDescription: '', description: '' });
  }

  save(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.busy()) return;
    const v = this.form.getRawValue();
    const current = this.editing();
    const request: RoomTypeRequest = {
      name: v.name.trim(), capacity: v.capacity!, totalUnits: v.totalUnits!, basePrice: v.basePrice!,
      bedDescription: v.bedDescription.trim() || null, description: v.description.trim() || null,
      version: current?.version,
    };
    this.busy.set(true);
    this.error.set(null);
    (current ? this.api.updateRoomType(current.id, request) : this.api.addRoomType(this.hotel().id, request))
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: () => {
          this.cancel();
          this.changed.emit();
        },
        error: (err) => {
          const e = toApiError(err);
          applyServerFieldErrors(this.form, e);
          this.error.set(e.code === 'ROOM_TYPE_NAME_TAKEN' ? 'A room type with this name already exists.'
            : e.code === 'CONCURRENT_MODIFICATION' ? 'This room type was changed meanwhile. Reload and try again.' : e.message);
          if (e.code === 'CONCURRENT_MODIFICATION') this.changed.emit();
        },
      });
  }

  toggle(r: EditableRoomType): void {
    this.busy.set(true);
    this.api
      .setRoomTypeStatus(r.id, r.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE')
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({ next: () => this.changed.emit(), error: (err) => this.error.set(toApiError(err).message) });
  }
}
