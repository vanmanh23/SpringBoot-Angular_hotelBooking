import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { ApiError, toApiError } from '../../core/http/api-error';
import { HotelRequest, HotelStatus, ManagedHotelSummary, ManageApi } from '../../core/manage/manage-api';
import { HotelForm } from './hotel-form';

/** "My hotels" for managers (only assigned hotels are returned) and every hotel for admins. */
@Component({
  selector: 'app-manage-hotels-page',
  imports: [RouterLink, HotelForm],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="head">
      <h1>{{ isAdmin ? 'All hotels' : 'My hotels' }}</h1>
      <button class="btn btn-primary" type="button" (click)="creating.set(!creating())">
        {{ creating() ? 'Close' : 'New hotel' }}
      </button>
    </header>

    @if (creating()) {
      <section class="card new">
        <h2>New hotel</h2>
        <p class="muted">New hotels start as drafts and become public once an administrator approves them.</p>
        <app-hotel-form submitLabel="Create draft" [saving]="saving()" [serverError]="createError()" (save)="create($event)" />
        @if (createError(); as e) { <p class="alert alert-error" role="alert">{{ e.message }}</p> }
      </section>
    }

    <div class="toolbar">
      <div class="field">
        <label for="mh-status">Status</label>
        <select id="mh-status" #status (change)="filter(status.value)">
          <option value="">All</option>
          @for (s of statuses; track s) { <option [value]="s">{{ s }}</option> }
        </select>
      </div>
    </div>

    @if (error()) { <p class="alert alert-error" role="alert">{{ error() }}</p> }
    <div class="table-wrap">
      <table class="table">
        <thead><tr><th>Name</th><th>City</th><th>Stars</th><th>Status</th></tr></thead>
        <tbody>
          @for (h of hotels(); track h.id) {
            <tr>
              <td><a [routerLink]="['/manage/hotels', h.id]">{{ h.name }}</a></td>
              <td>{{ h.city }}, {{ h.countryCode }}</td>
              <td>{{ h.starRating ?? '—' }}</td>
              <td><span class="status-pill">{{ h.status }}</span></td>
            </tr>
          } @empty {
            <tr><td colspan="4" class="muted">{{ loading() ? 'Loading…' : 'No hotels yet.' }}</td></tr>
          }
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
    .head { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
    .new { margin-bottom: 16px; }
    .pager { display: flex; gap: 12px; align-items: center; margin-top: 12px; }
  `,
})
export class ManageHotelsPage implements OnInit {
  private readonly api = inject(ManageApi);
  private readonly router = inject(Router);
  protected readonly isAdmin = inject(AuthService).hasRole('ADMIN');
  protected readonly statuses: HotelStatus[] = ['DRAFT', 'ACTIVE', 'INACTIVE', 'SUSPENDED'];

  protected readonly hotels = signal<ManagedHotelSummary[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly page = signal(0);
  protected readonly totalPages = signal(0);
  protected readonly creating = signal(false);
  protected readonly saving = signal(false);
  protected readonly createError = signal<ApiError | null>(null);
  private status: HotelStatus | null = null;

  ngOnInit(): void {
    this.load(0);
  }

  filter(value: string): void {
    this.status = (value || null) as HotelStatus | null;
    this.load(0);
  }

  load(page: number): void {
    this.loading.set(true);
    this.error.set(null);
    this.api
      .hotels(this.status, null, page)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (p) => {
          this.hotels.set(p.content);
          this.page.set(p.page);
          this.totalPages.set(p.totalPages);
        },
        error: (err) => this.error.set(toApiError(err).message),
      });
  }

  create(request: HotelRequest): void {
    this.saving.set(true);
    this.createError.set(null);
    this.api
      .createHotel(request)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (h) => this.router.navigate(['/manage/hotels', h.id]),
        error: (err) => this.createError.set(toApiError(err)),
      });
  }
}
