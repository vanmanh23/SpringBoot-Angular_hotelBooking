import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
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
    <app-search-form [initial]="query()" (search)="onSearch($event)" />

    <div class="layout">
      <aside class="filters card" aria-label="Filters">
        <h2>Filters</h2>
        <form [formGroup]="filters" (ngSubmit)="applyFilters()" novalidate>
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
            @for (hotel of hotels(); track hotel.id) {
              <article class="hotel card">
                @if (hotel.thumbnailUrl) {
                  <img [src]="hotel.thumbnailUrl" [alt]="hotel.name" loading="lazy" width="200" height="140" />
                } @else {
                  <div class="placeholder" aria-hidden="true">No photo</div>
                }
                <div class="info">
                  <h3><a [routerLink]="['/hotels', hotel.id]" [queryParams]="stayParams()">{{ hotel.name }}</a></h3>
                  <p class="muted">
                    @if (hotel.starRating) { <span aria-label="{{ hotel.starRating }} stars">{{ '★'.repeat(hotel.starRating) }}</span> · }
                    {{ hotel.address }}, {{ hotel.city }}
                  </p>
                  @if (hotel.avgRating) {
                    <p>Rated {{ hotel.avgRating }} / 5 ({{ hotel.reviewCount }} reviews)</p>
                  }
                  @if (hotel.amenities.length) {
                    <p class="muted amenities">{{ amenityNames(hotel.amenities) }}</p>
                  }
                </div>
                <div class="price-box">
                  <span class="muted">from</span>
                  <strong>{{ hotel.fromPrice | vnd }}</strong>
                  <span class="muted">per night</span>
                  <a class="btn btn-primary" [routerLink]="['/hotels', hotel.id]" [queryParams]="stayParams()">View rooms</a>
                </div>
              </article>
            } @empty {
              <div class="card empty">
                <h3>No hotels match your search</h3>
                <p class="muted">Try another destination, fewer filters or different dates.</p>
                <button class="btn btn-secondary" type="button" (click)="clearFilters()">Clear filters</button>
              </div>
            }
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
    .layout { display: grid; grid-template-columns: 260px 1fr; gap: 24px; margin-top: 24px; }
    @media (max-width: 800px) { .layout { grid-template-columns: 1fr; } }
    .filters h2 { margin-top: 0; font-size: 1.1rem; }
    fieldset { border: 0; padding: 0; margin: 0 0 16px; }
    legend { font-weight: 600; margin-bottom: 8px; }
    .price { display: flex; gap: 8px; }
    .price input { width: 100%; min-width: 0; padding: 8px; border: 1px solid var(--color-border); border-radius: var(--radius); font: inherit; }
    .check { display: block; margin: 4px 0; }
    .toolbar { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
    .sort select { margin-left: 8px; padding: 6px; font: inherit; }
    .note { font-size: 0.85rem; }
    .hotel { display: grid; grid-template-columns: 200px 1fr auto; gap: 16px; margin-bottom: 16px; padding: 16px; }
    @media (max-width: 700px) { .hotel { grid-template-columns: 1fr; } }
    .hotel img, .placeholder { width: 100%; max-width: 200px; height: 140px; object-fit: cover; border-radius: var(--radius); }
    .placeholder { display: grid; place-items: center; background: var(--color-bg); color: var(--color-muted); }
    .hotel h3 { margin: 0 0 4px; }
    .hotel p { margin: 4px 0; }
    .amenities { font-size: 0.9rem; }
    .price-box { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; min-width: 150px; }
    .price-box strong { font-size: 1.25rem; }
    @media (max-width: 700px) { .price-box { align-items: flex-start; } }
    .empty { text-align: center; }
    .pager { display: flex; justify-content: center; align-items: center; gap: 16px; margin-top: 16px; }
  `,
})
export class HotelSearchPage {
  private readonly api = inject(HotelApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly retry$ = new BehaviorSubject<void>(undefined);

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
