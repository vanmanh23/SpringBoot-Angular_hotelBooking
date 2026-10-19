import { ChangeDetectionStrategy, Component, inject, input, OnInit, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { toApiError } from '../../core/http/api-error';
import { ManageApi, ManagedHotelSummary } from '../../core/manage/manage-api';
import { AdminReviews } from './admin-reviews';
import { AdminBookings, AdminPayments } from './admin-transactions';
import { AdminUsers } from './admin-users';

type Section = 'users' | 'hotels' | 'bookings' | 'payments' | 'reviews';
const SECTIONS: { id: Section; label: string }[] = [
  { id: 'users', label: 'Users' }, { id: 'hotels', label: 'Hotel approvals' }, { id: 'bookings', label: 'Bookings' },
  { id: 'payments', label: 'Payments' }, { id: 'reviews', label: 'Reviews' },
];

/** Hotels waiting for approval (DRAFT); approval itself happens on the hotel's management page. */
@Component({
  selector: 'app-admin-hotel-approvals',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (error()) { <p class="alert alert-error" role="alert">{{ error() }}</p> }
    <div class="table-wrap">
      <table class="table">
        <thead><tr><th>Hotel</th><th>City</th><th></th></tr></thead>
        <tbody>
          @for (h of drafts(); track h.id) {
            <tr><td>{{ h.name }}</td><td>{{ h.city }}, {{ h.countryCode }}</td>
              <td><a [routerLink]="['/manage/hotels', h.id]">Review & approve</a></td></tr>
          } @empty { <tr><td colspan="3" class="muted">{{ loading() ? 'Loading…' : 'No hotel is waiting for approval.' }}</td></tr> }
        </tbody>
      </table>
    </div>
    <p><a routerLink="/manage">All hotels →</a></p>
  `,
})
export class AdminHotelApprovals implements OnInit {
  private readonly api = inject(ManageApi);
  protected readonly drafts = signal<ManagedHotelSummary[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.api.hotels('DRAFT', null, 0, 50).pipe(finalize(() => this.loading.set(false))).subscribe({
      next: (p) => this.drafts.set(p.content),
      error: (err) => this.error.set(toApiError(err).message),
    });
  }
}

@Component({
  selector: 'app-admin-page',
  imports: [AdminUsers, AdminHotelApprovals, AdminBookings, AdminPayments, AdminReviews],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Administration</h1>
    <div class="tabs" role="tablist">
      @for (s of sections; track s.id) {
        <button type="button" role="tab" [attr.aria-selected]="current() === s.id" (click)="open(s.id)">{{ s.label }}</button>
      }
    </div>
    @switch (current()) {
      @case ('users') { <app-admin-users /> }
      @case ('hotels') { <app-admin-hotel-approvals /> }
      @case ('bookings') { <app-admin-bookings /> }
      @case ('payments') { <app-admin-payments /> }
      @case ('reviews') { <app-admin-reviews /> }
    }
  `,
})
export class AdminPage implements OnInit {
  private readonly router = inject(Router);
  /** `?section=` keeps the open tab in the URL (shareable, survives reload). */
  readonly section = input<string | undefined>();
  protected readonly sections = SECTIONS;
  protected readonly current = signal<Section>('users');

  ngOnInit(): void {
    const s = SECTIONS.find((x) => x.id === this.section());
    if (s) this.current.set(s.id);
  }

  open(section: Section): void {
    this.current.set(section);
    this.router.navigate([], { queryParams: { section }, replaceUrl: true });
  }
}
