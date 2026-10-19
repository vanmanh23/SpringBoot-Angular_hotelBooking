import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { BehaviorSubject, catchError, combineLatest, map, of, startWith, switchMap } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { BookingApi } from '../../core/bookings/booking-api';
import { HotelApi } from '../../core/hotels/hotel-api';
import { HotelDetail, RoomType } from '../../core/hotels/hotel.models';
import { toQueryParams } from '../../core/hotels/search-query';
import { ApiError, toApiError } from '../../core/http/api-error';
import { nightsBetween } from '../../shared/forms/date-utils';
import { VndPipe } from '../../shared/pipes/vnd.pipe';
import { HotelReviews } from '../reviews/hotel-reviews';
import { SearchForm } from './search-form';

type DetailState =
  | { status: 'loading' }
  | { status: 'loaded'; hotel: HotelDetail; suitable: RoomType[] | null; available: Map<string, number> | null }
  | { status: 'error'; error: ApiError };

@Component({
  selector: 'app-hotel-detail-page',
  imports: [RouterLink, VndPipe, SearchForm, HotelReviews],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a routerLink="/hotels" [queryParams]="stayParams()" class="back">← Back to results</a>
    @switch (state().status) {
      @case ('loading') {
        <p class="muted" role="status">Loading hotel…</p>
      }
      @case ('error') {
        @if (notFound()) {
          <div class="card empty">
            <h1>Hotel not found</h1>
            <p class="muted">This hotel does not exist or is not available right now.</p>
            <a routerLink="/hotels">Search other hotels</a>
          </div>
        } @else {
          <div class="alert alert-error" role="alert">
            {{ errorMessage() }}
            <button class="btn btn-secondary" type="button" (click)="retry()">Try again</button>
          </div>
        }
      }
      @case ('loaded') {
        @if (hotel(); as h) {
          <header class="title">
            <h1>{{ h.name }}</h1>
            <p class="muted">
              @if (h.starRating) { <span aria-label="{{ h.starRating }} stars">{{ '★'.repeat(h.starRating) }}</span> · }
              {{ h.address }}, {{ h.city }}
              @if (h.avgRating) { · Rated {{ h.avgRating }} / 5 ({{ h.reviewCount }} reviews) }
            </p>
          </header>

          @if (h.images.length) {
            <section class="gallery" aria-label="Photos">
              <img class="main" [src]="selectedImage()?.url" [alt]="selectedImage()?.caption || h.name" width="800" height="450" />
              @if (h.images.length > 1) {
                <div class="thumbs">
                  @for (img of h.images; track img.id; let i = $index) {
                    <button type="button" class="thumb" [class.active]="i === imageIndex()" (click)="imageIndex.set(i)"
                            [attr.aria-label]="'Show photo ' + (i + 1)">
                      <img [src]="img.url" [alt]="img.caption || ''" loading="lazy" width="96" height="64" />
                    </button>
                  }
                </div>
              }
            </section>
          }

          <div class="grid">
            <section class="card">
              <h2>About</h2>
              <p>{{ h.description || 'No description provided.' }}</p>
              @if (h.amenities.length) {
                <h3>Amenities</h3>
                <ul class="tags">
                  @for (a of h.amenities; track a.code) { <li>{{ a.name }}</li> }
                </ul>
              }
            </section>
            <section class="card">
              <h2>Policies</h2>
              <p>Check-in from <strong>{{ h.checkInTime }}</strong> · Check-out until <strong>{{ h.checkOutTime }}</strong></p>
              <p class="muted">Times are local to the hotel ({{ h.timeZone }}).</p>
              <h3>Cancellation</h3>
              <p>{{ h.cancellationPolicy || 'See conditions at booking.' }}</p>
            </section>
          </div>

          <section class="rooms">
            <h2>Rooms</h2>
            <app-search-form [initial]="stay()" (search)="changeStay($event)" />
            @if (nights(); as n) {
              <p class="muted">{{ n }} {{ n === 1 ? 'night' : 'nights' }} · {{ stay().guests ?? 1 }} guests · {{ stay().rooms ?? 1 }} room(s)</p>
            }
            @for (room of roomList(); track room.id) {
              <article class="room card">
                <div>
                  <h3>{{ room.name }}</h3>
                  <p class="muted">Up to {{ room.capacity }} guests{{ room.bedDescription ? ' · ' + room.bedDescription : '' }}</p>
                  @if (room.description) { <p>{{ room.description }}</p> }
                  @if (room.amenities.length) {
                    <ul class="tags">
                      @for (a of room.amenities; track a.code) { <li>{{ a.name }}</li> }
                    </ul>
                  }
                </div>
                <div class="room-price">
                  <strong>{{ room.basePrice | vnd }}</strong>
                  <span class="muted">per room / night</span>
                  @if (!hasDates()) {
                    <button class="btn btn-secondary" type="button" disabled>Choose dates to book</button>
                  } @else if (unitsLeft(room.id) !== null && unitsLeft(room.id)! < roomCount()) {
                    <span class="sold-out" role="status">Sold out for these dates</span>
                  } @else {
                    @if (unitsLeft(room.id); as left) {
                      @if (left <= 3) { <span class="few-left">Only {{ left }} left</span> }
                    }
                    <button class="btn btn-primary" type="button" (click)="reserve(room.id)">
                      Reserve {{ roomCount() > 1 ? roomCount() + ' rooms' : '' }}
                    </button>
                  }
                </div>
              </article>
            } @empty {
              <div class="card empty">
                <p>No room type fits {{ stay().guests ?? 1 }} guests in {{ stay().rooms ?? 1 }} room(s).</p>
                <p class="muted">Try more rooms or fewer guests.</p>
              </div>
            }
            <p class="muted note">
              Prices are base rates per night. A {{ h.serviceFeePercent }}% service fee and {{ h.taxRatePercent }}% VAT are added;
              the final amount is calculated by our system when you book. Availability is indicative until your booking is created.
            </p>
          </section>

          <app-hotel-reviews [hotelId]="h.id" [avgRating]="h.avgRating" [reviewCount]="h.reviewCount" />
        }
      }
    }
  `,
  styles: `
    .back { display: inline-block; margin-bottom: 12px; }
    .title h1 { margin: 0 0 4px; }
    .gallery { margin: 16px 0; }
    .gallery .main { width: 100%; max-height: 450px; object-fit: cover; border-radius: var(--radius); }
    .thumbs { display: flex; gap: 8px; overflow-x: auto; margin-top: 8px; }
    .thumb { padding: 0; border: 2px solid transparent; border-radius: var(--radius); background: none; cursor: pointer; }
    .thumb.active { border-color: var(--color-primary); }
    .thumb img { display: block; width: 96px; height: 64px; object-fit: cover; border-radius: 6px; }
    .grid { display: grid; gap: 16px; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); margin: 16px 0; }
    .grid h2, .rooms h2 { margin-top: 0; }
    .tags { display: flex; flex-wrap: wrap; gap: 8px; list-style: none; padding: 0; margin: 8px 0; }
    .tags li { background: var(--color-bg); border: 1px solid var(--color-border); border-radius: 999px; padding: 2px 10px; font-size: 0.875rem; }
    .room { display: grid; grid-template-columns: 1fr auto; gap: 16px; margin: 12px 0; }
    @media (max-width: 600px) { .room { grid-template-columns: 1fr; } }
    .room h3 { margin: 0 0 4px; }
    .room-price { display: flex; flex-direction: column; align-items: flex-end; gap: 6px; }
    .room-price strong { font-size: 1.2rem; }
    .empty { text-align: center; }
    .note { font-size: 0.85rem; }
    .sold-out { color: var(--color-danger); font-weight: 600; }
    .few-left { color: var(--color-danger); font-size: 0.875rem; }
  `,
})
export class HotelDetailPage {
  private readonly api = inject(HotelApi);
  private readonly bookings = inject(BookingApi);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly retry$ = new BehaviorSubject<void>(undefined);

  /** Route param and query params (withComponentInputBinding). */
  readonly id = input.required<string>();
  readonly checkIn = input<string | undefined>();
  readonly checkOut = input<string | undefined>();
  readonly guests = input<string | undefined>();
  readonly rooms = input<string | undefined>();

  protected readonly imageIndex = signal(0);

  protected readonly stay = computed(() => {
    const toInt = (v: string | undefined, min: number, max: number) => {
      const n = Number(v);
      return v && Number.isInteger(n) && n >= min && n <= max ? n : undefined;
    };
    const both = this.checkIn() && this.checkOut();
    return {
      checkIn: both ? this.checkIn() : undefined,
      checkOut: both ? this.checkOut() : undefined,
      guests: toInt(this.guests(), 1, 40),
      rooms: toInt(this.rooms(), 1, 10),
    };
  });

  protected readonly stayParams = computed(() => toQueryParams(this.stay()));

  protected readonly state = toSignal(
    combineLatest([toObservable(this.id), toObservable(this.stay), this.retry$]).pipe(
      switchMap(([id, stay]) =>
        this.api.detail(id).pipe(
          switchMap((hotel) =>
            combineLatest([
              stay.guests || stay.rooms ? this.api.roomTypes(id, stay.guests ?? 1, stay.rooms ?? 1) : of(null),
              stay.checkIn && stay.checkOut
                ? this.bookings.availability(id, stay.checkIn, stay.checkOut).pipe(
                    map((a) => new Map(a.roomTypes.map((r) => [r.roomTypeId, r.availableUnits]))),
                    catchError(() => of(null)), // advisory only: never block the page on it
                  )
                : of(null),
            ]).pipe(map(([suitable, available]) => ({ hotel, suitable, available }))),
          ),
          map(({ hotel, suitable, available }): DetailState => ({ status: 'loaded', hotel, suitable, available })),
          catchError((err) => of<DetailState>({ status: 'error', error: toApiError(err) })),
          startWith<DetailState>({ status: 'loading' }),
        ),
      ),
    ),
    { initialValue: { status: 'loading' } as DetailState },
  );

  protected readonly hotel = computed(() => {
    const s = this.state();
    return s.status === 'loaded' ? s.hotel : null;
  });
  protected readonly roomList = computed(() => {
    const s = this.state();
    return s.status === 'loaded' ? (s.suitable ?? s.hotel.roomTypes) : [];
  });
  protected readonly selectedImage = computed(() => this.hotel()?.images[this.imageIndex()] ?? null);
  protected readonly nights = computed(() => {
    const { checkIn, checkOut } = this.stay();
    return checkIn && checkOut ? nightsBetween(checkIn, checkOut) : null;
  });
  protected readonly notFound = computed(() => {
    const s = this.state();
    return s.status === 'error' && s.error.status === 404;
  });
  protected readonly errorMessage = computed(() => {
    const s = this.state();
    return s.status === 'error' ? s.error.message : '';
  });

  protected readonly hasDates = computed(() => !!(this.stay().checkIn && this.stay().checkOut));
  protected readonly roomCount = computed(() => this.stay().rooms ?? 1);

  unitsLeft(roomTypeId: string): number | null {
    const s = this.state();
    return s.status === 'loaded' && s.available ? (s.available.get(roomTypeId) ?? 0) : null;
  }

  /** Goes to checkout; anonymous users sign in first and come back to the same checkout URL. */
  reserve(roomTypeId: string): void {
    const { checkIn, checkOut, guests } = this.stay();
    const queryParams = toQueryParams({ checkIn, checkOut, guests: guests ?? 1 });
    const target = this.router.createUrlTree(['/booking', 'new'], {
      queryParams: { ...queryParams, hotelId: this.id(), roomTypeId, quantity: this.roomCount() },
    });
    if (this.auth.isAuthenticated()) {
      void this.router.navigateByUrl(target);
    } else {
      void this.router.navigate(['/login'], { queryParams: { returnUrl: this.router.serializeUrl(target) } });
    }
  }

  changeStay(stay: { checkIn?: string; checkOut?: string; guests?: number; rooms?: number }): void {
    void this.router.navigate(['/hotels', this.id()], { queryParams: toQueryParams(stay), replaceUrl: true });
  }

  retry(): void {
    this.retry$.next();
  }
}
