import { ChangeDetectionStrategy, Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize, Observable } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { Amenity } from '../../core/hotels/hotel.models';
import { HotelApi } from '../../core/hotels/hotel-api';
import { ApiError, toApiError } from '../../core/http/api-error';
import { HotelRequest, HotelStatus, ManagedHotel, ManageApi } from '../../core/manage/manage-api';
import { LoadError } from '../../shared/ui/load-error';
import { HotelForm } from './hotel-form';
import { HotelManagers } from './hotel-managers';
import { ManageBookings } from './manage-bookings';
import { ManageRoomTypes } from './manage-room-types';

type Tab = 'details' | 'rooms' | 'photos' | 'bookings' | 'managers';

/** Transitions the UI offers; hotel-service enforces the real state machine (HotelStatus.canTransitionTo). */
export function offeredTransitions(status: HotelStatus, admin: boolean, wasApproved: boolean): HotelStatus[] {
  switch (status) {
    case 'DRAFT': return admin ? ['ACTIVE', 'SUSPENDED'] : [];
    case 'ACTIVE': return admin ? ['INACTIVE', 'SUSPENDED'] : ['INACTIVE'];
    case 'INACTIVE': return [...(admin || wasApproved ? ['ACTIVE' as const] : []), ...(admin ? ['SUSPENDED' as const] : [])];
    case 'SUSPENDED': return admin ? ['ACTIVE', 'INACTIVE'] : [];
  }
}

@Component({
  selector: 'app-manage-hotel-page',
  imports: [RouterLink, ReactiveFormsModule, HotelForm, ManageRoomTypes, ManageBookings, HotelManagers, LoadError],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a routerLink="/manage" class="back">← Hotels</a>
    @if (loadError()) {
      <app-load-error [message]="loadError()" (retry)="reload()" />
    } @else if (hotel(); as h) {
      <header class="head">
        <div>
          <h1>{{ h.name }}</h1>
          <p class="muted">{{ h.city }}, {{ h.countryCode }} · <span class="status-pill">{{ h.status }}</span>
            @if (h.avgRating) { · {{ h.avgRating }}/5 ({{ h.reviewCount }} reviews) }</p>
        </div>
        <div class="status-actions">
          @for (t of transitions(); track t) {
            <button class="btn btn-secondary btn-small" type="button" [disabled]="busy()" (click)="setStatus(t)">{{ actionLabel(t) }}</button>
          }
          @if (h.status === 'ACTIVE') { <a [routerLink]="['/hotels', h.id]">View public page</a> }
        </div>
      </header>
      @if (h.status === 'DRAFT' && !isAdmin) {
        <p class="alert alert-pending" role="status">This hotel is a draft. An administrator must approve it before guests can see it.</p>
      }
      @if (message()) { <p class="alert alert-success" role="status">{{ message() }}</p> }
      @if (actionError()) { <p class="alert alert-error" role="alert">{{ actionError() }}</p> }

      <div class="tabs" role="tablist">
        @for (t of tabs(); track t.id) {
          <button type="button" role="tab" [attr.aria-selected]="tab() === t.id" (click)="tab.set(t.id)">{{ t.label }}</button>
        }
      </div>

      @switch (tab()) {
        @case ('details') {
          <section class="card">
            <app-hotel-form [initial]="h" [saving]="busy()" [serverError]="formError()" (save)="saveDetails($event)" />
          </section>
          <section class="card">
            <h2>Hotel amenities</h2>
            <div class="amenities">
              @for (a of hotelAmenities(); track a.code) {
                <label><input type="checkbox" [checked]="selectedAmenities().has(a.code)" (change)="toggleAmenity(a.code)" /> {{ a.name }}</label>
              }
            </div>
            <button class="btn btn-secondary" type="button" [disabled]="busy()" (click)="saveAmenities()">Save amenities</button>
          </section>
        }
        @case ('rooms') { <app-manage-room-types [hotel]="h" (changed)="reload()" /> }
        @case ('photos') {
          <section class="card">
            <h2>Photos</h2>
            <ul class="photos">
              @for (img of h.images; track img.id) {
                <li>
                  <img [src]="img.url" [alt]="img.caption || h.name" width="160" height="100" loading="lazy" />
                  <span>{{ img.caption }}</span>
                  <button class="btn btn-secondary btn-small" type="button" [disabled]="busy()" (click)="deleteImage(img.id)">Remove</button>
                </li>
              } @empty { <li class="muted">No photos yet.</li> }
            </ul>
            <form [formGroup]="imageForm" (ngSubmit)="addImage()" class="toolbar" novalidate>
              <div class="field"><label for="img-url">Image URL (https)</label><input id="img-url" formControlName="url" /></div>
              <div class="field"><label for="img-caption">Caption</label><input id="img-caption" formControlName="caption" maxlength="200" /></div>
              <button class="btn btn-primary" type="submit" [disabled]="busy() || imageForm.invalid">Add photo</button>
            </form>
          </section>
        }
        @case ('bookings') { <app-manage-bookings [hotelId]="h.id" /> }
        @case ('managers') { <app-hotel-managers [hotel]="h" (changed)="reload()" /> }
      }
    } @else {
      <p class="muted" role="status">Loading hotel…</p>
    }
  `,
  styles: `
    .back { display: inline-block; margin-bottom: 12px; }
    .head { display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; align-items: flex-start; }
    .head h1 { margin: 0; }
    .status-actions { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
    .alert-pending { background: #fffaeb; color: #93370d; }
    .card { margin-bottom: 16px; }
    h2 { margin-top: 0; font-size: 1.1rem; }
    .amenities { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 6px; margin-bottom: 12px; }
    .photos { list-style: none; padding: 0; display: flex; flex-direction: column; gap: 8px; }
    .photos li { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
    .photos img { object-fit: cover; border-radius: var(--radius); }
  `,
})
export class ManageHotelPage implements OnInit {
  private readonly api = inject(ManageApi);
  private readonly hotels = inject(HotelApi);
  protected readonly isAdmin = inject(AuthService).hasRole('ADMIN');

  readonly id = input.required<string>();

  protected readonly hotel = signal<ManagedHotel | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly message = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly formError = signal<ApiError | null>(null);
  protected readonly tab = signal<Tab>('details');
  protected readonly hotelAmenities = signal<Amenity[]>([]);
  protected readonly selectedAmenities = signal(new Set<string>());

  protected readonly tabs = computed(() => {
    const list: { id: Tab; label: string }[] = [
      { id: 'details', label: 'Details' }, { id: 'rooms', label: 'Room types' }, { id: 'photos', label: 'Photos' },
      { id: 'bookings', label: 'Bookings' },
    ];
    return this.isAdmin ? [...list, { id: 'managers' as Tab, label: 'Managers' }] : list;
  });
  protected readonly transitions = computed(() => {
    const h = this.hotel();
    return h ? offeredTransitions(h.status, this.isAdmin, !!h.approvedAt) : [];
  });

  protected readonly imageForm = new FormGroup({
    url: new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.pattern(/^https:\/\/\S+$/), Validators.maxLength(1000)] }),
    caption: new FormControl('', { nonNullable: true }),
  });

  ngOnInit(): void {
    this.reload();
    this.hotels.amenities('HOTEL').subscribe({ next: (a) => this.hotelAmenities.set(a), error: () => undefined });
  }

  reload(): void {
    this.loadError.set(null);
    this.api.hotel(this.id()).subscribe({
      next: (h) => this.setHotel(h),
      error: (err) => {
        const e = toApiError(err);
        this.loadError.set(e.status === 403 ? 'You are not assigned to this hotel.' : e.status === 404 ? 'Hotel not found.' : e.message);
      },
    });
  }

  saveDetails(request: HotelRequest): void {
    this.formError.set(null);
    this.run(this.api.updateHotel(this.id(), request), 'Details saved.', (e) => this.formError.set(e));
  }

  setStatus(status: HotelStatus): void {
    this.run(this.api.setStatus(this.id(), status), `Status changed to ${status}.`);
  }

  toggleAmenity(code: string): void {
    this.selectedAmenities.update((s) => {
      const next = new Set(s);
      if (next.has(code)) next.delete(code); else next.add(code);
      return next;
    });
  }

  saveAmenities(): void {
    this.run(this.api.setAmenities(this.id(), [...this.selectedAmenities()]), 'Amenities saved.');
  }

  addImage(): void {
    const v = this.imageForm.getRawValue();
    this.busy.set(true);
    this.api.addImage(this.id(), v.url.trim(), v.caption.trim() || null).pipe(finalize(() => this.busy.set(false))).subscribe({
      next: () => {
        this.imageForm.reset();
        this.reload();
      },
      error: (err) => this.actionError.set(toApiError(err).message),
    });
  }

  deleteImage(imageId: string): void {
    this.busy.set(true);
    this.api.deleteImage(this.id(), imageId).pipe(finalize(() => this.busy.set(false))).subscribe({
      next: () => this.reload(),
      error: (err) => this.actionError.set(toApiError(err).message),
    });
  }

  protected actionLabel(status: HotelStatus): string {
    const h = this.hotel();
    if (status === 'ACTIVE') return h?.status === 'DRAFT' ? 'Approve & publish' : 'Activate';
    return { INACTIVE: 'Deactivate', SUSPENDED: 'Suspend', DRAFT: 'Draft' }[status];
  }

  private run(call: Observable<ManagedHotel>, success: string, onError?: (e: ApiError) => void): void {
    this.busy.set(true);
    this.message.set(null);
    this.actionError.set(null);
    call.pipe(finalize(() => this.busy.set(false))).subscribe({
      next: (h) => {
        this.setHotel(h);
        this.message.set(success);
      },
      error: (err) => {
        const e = toApiError(err);
        this.actionError.set(e.code === 'CONCURRENT_MODIFICATION'
          ? 'Someone else changed this hotel meanwhile. The latest version has been loaded; please re-apply your change.'
          : e.message);
        onError?.(e);
        if (e.code === 'CONCURRENT_MODIFICATION' || e.code === 'INVALID_STATUS_TRANSITION') this.reload();
      },
    });
  }

  private setHotel(h: ManagedHotel): void {
    this.hotel.set(h);
    this.selectedAmenities.set(new Set(h.amenities.map((a) => a.code)));
  }
}
