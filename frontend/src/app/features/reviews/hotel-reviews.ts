import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';
import { toApiError } from '../../core/http/api-error';
import { Review, ReviewApi } from '../../core/reviews/review-api';
import { LoadError } from '../../shared/ui/load-error';

/** Public review list of a hotel (VISIBLE reviews only). Admins can hide a review in place. */
@Component({
  selector: 'app-hotel-reviews',
  imports: [DatePipe, LoadError],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="card reviews" aria-labelledby="reviews-title">
      <h2 id="reviews-title">Guest reviews</h2>
      @if (avgRating(); as avg) {
        <p class="score"><strong>{{ avg }}</strong> / 5 · {{ reviewCount() }} {{ reviewCount() === 1 ? 'review' : 'reviews' }}</p>
      }
      <app-load-error [message]="error()" (retry)="retry()" />
      @for (r of reviews(); track r.id) {
        <article class="review">
          <header>
            <span class="stars" [attr.aria-label]="r.rating + ' out of 5'">{{ stars(r.rating) }}</span>
            <span class="muted">{{ r.author }}{{ r.mine ? ' (you)' : '' }} · {{ r.createdAt | date: 'mediumDate' }}</span>
          </header>
          @if (r.comment) { <p class="comment">{{ r.comment }}</p> }
          @if (isAdmin()) {
            <button class="btn btn-secondary small" type="button" (click)="hide(r)" [disabled]="busy() === r.id">Hide review</button>
          }
        </article>
      } @empty {
        @if (!loading()) { <p class="muted">No reviews yet. Reviews come only from guests who completed a stay.</p> }
      }
      @if (loading()) { <p class="muted" role="status">Loading reviews…</p> }
      @if (hasMore() && !loading()) {
        <button class="btn btn-secondary" type="button" (click)="more()">Show more reviews</button>
      }
    </section>
  `,
  styles: `
    .reviews { margin-top: 16px; }
    h2 { margin-top: 0; font-size: 1.1rem; }
    .score { font-size: 1.05rem; }
    .review { border-top: 1px solid var(--color-border); padding: 12px 0; }
    .review header { display: flex; gap: 12px; flex-wrap: wrap; align-items: baseline; }
    .stars { color: #b54708; letter-spacing: 1px; }
    .comment { margin: 6px 0; white-space: pre-line; overflow-wrap: anywhere; }
    .small { padding: 4px 10px; font-size: 0.875rem; }
  `,
})
export class HotelReviews implements OnInit {
  private readonly api = inject(ReviewApi);
  private readonly auth = inject(AuthService);

  readonly hotelId = input.required<string>();
  readonly avgRating = input<number | null>(null);
  readonly reviewCount = input(0);

  protected readonly reviews = signal<Review[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal<string | null>(null);
  private readonly page = signal(0);
  private readonly totalPages = signal(0);
  protected readonly hasMore = computed(() => this.page() + 1 < this.totalPages());
  protected readonly isAdmin = computed(() => !!this.auth.user() && this.auth.hasRole('ADMIN'));

  ngOnInit(): void {
    this.load(0);
  }

  retry(): void {
    this.load(this.reviews().length ? this.page() + 1 : 0);
  }

  more(): void {
    this.load(this.page() + 1);
  }

  hide(review: Review): void {
    this.busy.set(review.id);
    this.api.setVisibility(review.id, 'HIDDEN', 'Hidden from hotel page').subscribe({
      next: () => {
        this.busy.set(null);
        this.reviews.update((list) => list.filter((r) => r.id !== review.id));
      },
      error: (err) => {
        this.busy.set(null);
        this.error.set(toApiError(err).message);
      },
    });
  }

  protected stars(rating: number): string {
    return '★'.repeat(rating) + '☆'.repeat(5 - rating);
  }

  private load(page: number): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.forHotel(this.hotelId(), page).subscribe({
      next: (p) => {
        this.loading.set(false);
        this.page.set(p.page);
        this.totalPages.set(p.totalPages);
        this.reviews.update((list) => (page === 0 ? p.content : [...list, ...p.content]));
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(toApiError(err).message);
      },
    });
  }
}
