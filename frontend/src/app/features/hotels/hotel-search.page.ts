import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { BehaviorSubject, catchError, combineLatest, map, of, startWith, switchMap } from 'rxjs';
import { HotelApi } from '../../core/hotels/hotel-api';
import { HotelSearchQuery, HotelSort, HotelSummary } from '../../core/hotels/hotel.models';
import { parseSearchQuery, toQueryParams } from '../../core/hotels/search-query';
import { Page } from '../../core/http/page';
import { ApiError, toApiError } from '../../core/http/api-error';
import { VndPipe } from '../../shared/pipes/vnd.pipe';
import { SearchForm } from './search-form';

type SearchState =
  | { status: 'loading' }
  | { status: 'loaded'; page: Page<HotelSummary> }
  | { status: 'error'; error: ApiError };

const PAGE_SIZE = 10;

/**
 * The URL is the single source of truth: every filter/page change navigates, and results are derived from the
 * query params with switchMap, so stale responses are cancelled and links can be shared or bookmarked.
 */
@Component({
  selector: 'app-hotel-search-page',
  imports: [ReactiveFormsModule, RouterLink, SearchForm, VndPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-search-form [initial]="query()" (searched)="onSearch($event)" />

    <div class="layout">
      <aside class="filters card" aria-label="Filters" [class.open]="filtersOpen()">
        <button class="filters-toggle btn btn-secondary btn-small" type="button" aria-controls="filters-body"
                [attr.aria-expanded]="filtersOpen()" (click)="filtersOpen.set(!filtersOpen())">
          <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true" focusable="false">
            <path d="M3 5h18v2H3zm4 6h10v2H7zm4 6h2v2h-2z" fill="currentColor"/>
          </svg>
          Filters
        </button>
        <h2 class="filters-title">Filters</h2>
        <form id="filters-body" class="filters-body" [formGroup]="filters" (ngSubmit)="applyFilters()" novalidate>
          <fieldset>
            <legend>Price per night (VND)</legend>
            <div class="price">
              <input type="number" min="0" step="50000" formControlName="minPrice" placeholder="Min" aria-label="Minimum price" />
              <input type="number" min="0" step="50000" formControlName="maxPrice" placeholder="Max" aria-label="Maximum price" />
            </div>
          </fieldset>
          <fieldset>
            <legend>Star rating</legend>
            @for (star of starOptions; track star) {
              <label class="check"><input type="checkbox" [checked]="hasStar(star)" (change)="toggleStar(star)" /> {{ star }} ★</label>
            }
          </fieldset>
          <fieldset>
            <legend>Amenities</legend>
            @for (a of amenityOptions(); track a.code) {
              <label class="check"><input type="checkbox" [checked]="hasAmenity(a.code)" (change)="toggleAmenity(a.code)" /> {{ a.name }}</label>
            } @empty {
              <p class="muted">No amenities available.</p>
            }
          </fieldset>
          <button class="btn btn-secondary btn-block" type="submit">Apply price</button>
        </form>
      </aside>

      <section class="results" aria-live="polite">
        <div class="toolbar">
          @if (state(); as s) {
            @if (s.status === 'loaded') {
              <span class="muted">{{ s.page.totalElements }} {{ s.page.totalElements === 1 ? 'hotel' : 'hotels' }}</span>
            }
          }
          <label class="sort">
            Sort by
            <select [value]="query().sort ?? 'recommended'" (change)="changeSort($any($event.target).value)">
              <option value="recommended">Recommended</option>
              <option value="price_asc">Price: low to high</option>
              <option value="price_desc">Price: high to low</option>
              <option value="rating_desc">Guest rating</option>
              <option value="name_asc">Name</option>
            </select>
          </label>
        </div>
        <p class="muted note">Prices and availability are indicative and are confirmed when you book.</p>

        @switch (state().status) {
          @case ('loading') {
            <p class="muted" role="status">Searching hotels…</p>
          }
          @case ('error') {
            <div class="alert alert-error" role="alert">
              {{ errorMessage() }}
              <button class="btn btn-secondary" type="button" (click)="retry()">Try again</button>
            </div>
          }
          @case ('loaded') {
            <div class="grid-list">
            @for (hotel of hotels(); track hotel.id) {
              <article class="hotel">
                <a class="photo" [routerLink]="['/hotels', hotel.id]" [queryParams]="stayParams()" tabindex="-1" aria-hidden="true">
                  @if (hotel.thumbnailUrl) {
                    <img [src]="hotel.thumbnailUrl" alt="" loading="lazy" width="400" height="300" />
                  } @else {
                    <span class="placeholder">No photo</span>
                  }
                  @if (hotel.avgRating) { <span class="rating-chip">★ {{ hotel.avgRating }}</span> }
                </a>
                <div class="info">
                  <h3><a [routerLink]="['/hotels', hotel.id]" [queryParams]="stayParams()">{{ hotel.name }}</a></h3>
                  <p class="muted">
                    @if (hotel.starRating) { <span aria-label="{{ hotel.starRating }} stars">{{ '★'.repeat(hotel.starRating) }}</span> · }
                    {{ hotel.address }}, {{ hotel.city }}
                  </p>
                  @if (hotel.avgRating) {
                    <p class="muted small">Rated {{ hotel.avgRating }} / 5 ({{ hotel.reviewCount }} reviews)</p>
                  }
                  @if (hotel.amenities.length) {
                    <p class="muted amenities">{{ amenityNames(hotel.amenities) }}</p>
                  }
                </div>
                <div class="price-box">
                  <span class="price"><span class="muted">from&nbsp;</span><strong>{{ hotel.fromPrice | vnd }}</strong><span class="muted">&nbsp;/ night</span></span>
                  <a class="btn btn-secondary btn-small" [routerLink]="['/hotels', hotel.id]" [queryParams]="stayParams()">View rooms</a>
                </div>
              </article>
            } @empty {
              <div class="card empty">
                <h3>No hotels match your search</h3>
                <p class="muted">Try another destination, fewer filters or different dates.</p>
                <button class="btn btn-secondary" type="button" (click)="clearFilters()">Clear filters</button>
              </div>
            }
            </div>
            @if (totalPages() > 1) {
              <nav class="pager" aria-label="Pagination">
                <button class="btn btn-secondary" type="button" [disabled]="currentPage() === 0" (click)="goToPage(currentPage() - 1)">Previous</button>
                <span>Page {{ currentPage() + 1 }} of {{ totalPages() }}</span>
                <button class="btn btn-secondary" type="button" [disabled]="currentPage() + 1 >= totalPages()" (click)="goToPage(currentPage() + 1)">Next</button>
              </nav>
            }
          }
        }
      </section>
    </div>
  `,
  styles: `
    .layout { display: grid; grid-template-columns: 250px 1fr; gap: 32px; margin-top: 32px; }
    @media (max-width: 860px) { .layout { grid-template-columns: 1fr; } }
    .filters { align-self: start; position: sticky; top: 96px; box-shadow: none; }
    @media (max-width: 860px) { .filters { position: static; } }
    .filters h2 { margin-top: 0; }
    .filters-toggle { display: none; }
    @media (max-width: 860px) {
      .filters { padding: 0; border: 0; box-shadow: none; }
      .filters-toggle { display: inline-flex; border-radius: var(--radius-pill); }
      .filters-title, .filters-body { display: none; }
      .filters.open { padding: 20px; border: 1px solid var(--color-border); }
      .filters.open .filters-toggle { margin-bottom: 12px; }
      .filters.open .filters-body { display: block; }
    }
    fieldset { border: 0; padding: 0 0 16px; margin: 0 0 16px; border-bottom: 1px solid var(--color-border); }
    legend { font-weight: 600; margin-bottom: 10px; }
    .price { display: flex; gap: 8px; }
    .price input { width: 100%; min-width: 0; padding: 10px 12px; border: 1px solid var(--color-border-strong); border-radius: var(--radius-sm); font: inherit; }
    .check { display: flex; align-items: center; gap: 10px; margin: 8px 0; cursor: pointer; }
    .toolbar { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 4px; }
    .sort select { margin-left: 8px; padding: 8px 12px; font: inherit; border: 1px solid var(--color-border-strong); border-radius: var(--radius-pill); background: #fff; }
    .note { font-size: 0.85rem; margin-top: 0; }
    .grid-list { display: grid; gap: 28px 20px; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); }
    .hotel { display: flex; flex-direction: column; gap: 6px; }
    .photo { position: relative; display: block; aspect-ratio: 4 / 3; border-radius: var(--radius); overflow: hidden; background: var(--color-surface-alt); }
    .photo img { width: 100%; height: 100%; object-fit: cover; transition: transform 0.3s; }
    .hotel:hover .photo img { transform: scale(1.04); }
    .placeholder { display: grid; place-items: center; height: 100%; color: var(--color-muted); }
    .rating-chip { position: absolute; top: 12px; left: 12px; background: #fff; border-radius: var(--radius-pill); padding: 3px 10px;
      font-size: 0.8rem; font-weight: 700; box-shadow: var(--shadow-sm); }
    .hotel h3 { margin: 6px 0 0; font-size: 1rem; }
    .hotel h3 a { text-decoration: none; }
    .hotel p { margin: 0; }
    .small, .amenities { font-size: 0.875rem; }
    .amenities { display: -webkit-box; -webkit-line-clamp: 1; -webkit-box-orient: vertical; overflow: hidden; }
    .price-box { display: flex; justify-content: space-between; align-items: center; gap: 8px; margin-top: 4px; flex-wrap: wrap; }
    .empty { grid-column: 1 / -1; text-align: center; }
    .pager { display: flex; justify-content: center; align-items: center; gap: 16px; margin-top: 32px; }
  `,
})
export class HotelSearchPage {
  private readonly api = inject(HotelApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly retry$ = new BehaviorSubject<void>(undefined);
  /** Mobile only: filters collapse behind a button (always visible on wide screens). */
  protected readonly filtersOpen = signal(false);

  protected readonly starOptions = [5, 4, 3, 2, 1];
  protected readonly query = toSignal(this.route.queryParamMap.pipe(map(parseSearchQuery)), { initialValue: {} as HotelSearchQuery });

  protected readonly state = toSignal(
    combineLatest([this.route.queryParamMap.pipe(map(parseSearchQuery)), this.retry$]).pipe(
      switchMap(([query]) =>
        this.api.search({ ...query, size: PAGE_SIZE }).pipe(
          map((page): SearchState => ({ status: 'loaded', page })),
          catchError((err) => of<SearchState>({ status: 'error', error: toApiError(err) })),
          startWith<SearchState>({ status: 'loading' }),
        ),
      ),
    ),
    { initialValue: { status: 'loading' } as SearchState },
  );

  protected readonly amenityOptions = toSignal(
    this.api.amenities('HOTEL').pipe(catchError(() => of([]))),
    { initialValue: [] },
  );

  protected readonly hotels = computed(() => {
    const s = this.state();
    return s.status === 'loaded' ? s.page.content : [];
  });
  protected readonly totalPages = computed(() => {
    const s = this.state();
    return s.status === 'loaded' ? s.page.totalPages : 0;
  });
  protected readonly currentPage = computed(() => this.query().page ?? 0);
  protected readonly errorMessage = computed(() => {
    const s = this.state();
    if (s.status !== 'error') return '';
    return s.error.code === 'VALIDATION_FAILED'
      ? 'Some search values are invalid. Please adjust them and search again.'
      : s.error.message;
  });
  protected readonly stayParams = computed(() => {
    const { checkIn, checkOut, guests, rooms } = this.query();
    return toQueryParams({ checkIn, checkOut, guests, rooms });
  });

  protected readonly filters = inject(FormBuilder).group({
    minPrice: [null as number | null],
    maxPrice: [null as number | null],
  });

  constructor() {
    this.route.queryParamMap.pipe(map(parseSearchQuery), takeUntilDestroyed()).subscribe((q) =>
      this.filters.reset({ minPrice: q.minPrice ?? null, maxPrice: q.maxPrice ?? null }, { emitEvent: false }),
    );
  }

  onSearch(criteria: HotelSearchQuery): void {
    this.navigate({ ...this.query(), ...criteria, page: undefined });
  }

  applyFilters(): void {
    const { minPrice, maxPrice } = this.filters.getRawValue();
    this.navigate({ ...this.query(), minPrice: minPrice ?? undefined, maxPrice: maxPrice ?? undefined, page: undefined });
  }

  changeSort(sort: HotelSort): void {
    this.navigate({ ...this.query(), sort: sort === 'recommended' ? undefined : sort, page: undefined });
  }

  toggleStar(star: number): void {
    const stars = this.query().stars ?? [];
    const next = stars.includes(star) ? stars.filter((s) => s !== star) : [...stars, star].sort();
    this.navigate({ ...this.query(), stars: next, page: undefined });
  }

  toggleAmenity(code: string): void {
    const codes = this.query().amenities ?? [];
    const next = codes.includes(code) ? codes.filter((c) => c !== code) : [...codes, code].sort();
    this.navigate({ ...this.query(), amenities: next, page: undefined });
  }

  hasStar(star: number): boolean {
    return this.query().stars?.includes(star) ?? false;
  }

  hasAmenity(code: string): boolean {
    return this.query().amenities?.includes(code) ?? false;
  }

  amenityNames(codes: string[]): string {
    const names = new Map(this.amenityOptions().map((a) => [a.code, a.name]));
    return codes.map((c) => names.get(c) ?? c).join(' · ');
  }

  goToPage(page: number): void {
    this.navigate({ ...this.query(), page: page > 0 ? page : undefined });
  }

  clearFilters(): void {
    const { city, checkIn, checkOut, guests, rooms } = this.query();
    this.navigate({ city, checkIn, checkOut, guests, rooms });
  }

  retry(): void {
    this.retry$.next();
  }

  private navigate(query: HotelSearchQuery): void {
    void this.router.navigate(['/hotels'], { queryParams: toQueryParams(query) });
  }
}
