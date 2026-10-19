import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { toApiError } from '../../core/http/api-error';
import { LoadError } from '../../shared/ui/load-error';
import { AdminReview, ReviewApi, ReviewStatus } from '../../core/reviews/review-api';

/** Review moderation: hide abusive reviews, restore hidden ones. Hotel scores update automatically (D-028). */
@Component({
  selector: 'app-admin-reviews',
  imports: [DatePipe, RouterLink, LoadError],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toolbar">
      <div class="field"><label for="ar-status">Status</label>
        <select id="ar-status" #st (change)="apply(st.value)">
          <option value="">All</option><option value="VISIBLE">Visible</option><option value="HIDDEN">Hidden</option>
        </select></div>
    </div>
    <app-load-error [message]="error()" (retry)="load(page())" />
    <ul class="list">
      @for (r of reviews(); track r.id) {
        <li class="card item">
          <div class="meta">
            <span>{{ '★'.repeat(r.rating) }}{{ '☆'.repeat(5 - r.rating) }} · <a [routerLink]="['/hotels', r.hotelId]">hotel</a> ·
              {{ r.createdAt | date: 'medium' }}</span>
            <span class="status-pill">{{ r.status }}</span>
          </div>
          @if (r.comment) { <p class="comment">{{ r.comment }}</p> }
          @if (r.hiddenReason) { <p class="muted">Hidden: {{ r.hiddenReason }}</p> }
          @if (r.status === 'VISIBLE') {
            <div class="toolbar">
              <div class="field"><label [for]="'reason-' + r.id">Reason</label><input [id]="'reason-' + r.id" #reason maxlength="300" /></div>
              <button class="btn btn-secondary btn-small" type="button" [disabled]="busy() === r.id" (click)="set(r, 'HIDDEN', reason.value)">Hide</button>
            </div>
          } @else {
            <button class="btn btn-secondary btn-small" type="button" [disabled]="busy() === r.id" (click)="set(r, 'VISIBLE', '')">Restore</button>
          }
        </li>
      } @empty { <li class="muted">{{ loading() ? 'Loading…' : 'No reviews.' }}</li> }
    </ul>
    @if (page() + 1 < totalPages()) {
      <button class="btn btn-secondary" type="button" (click)="load(page() + 1)">Next page</button>
    }
  `,
  styles: `
    .list { list-style: none; padding: 0; display: flex; flex-direction: column; gap: 8px; }
    .item { padding: 16px; }
    .meta { display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
    .comment { white-space: pre-line; overflow-wrap: anywhere; }
  `,
})
export class AdminReviews implements OnInit {
  private readonly api = inject(ReviewApi);
  protected readonly reviews = signal<AdminReview[]>([]);
  protected readonly loading = signal(false);
  protected readonly busy = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly page = signal(0);
  protected readonly totalPages = signal(0);
  private status: ReviewStatus | null = null;

  ngOnInit(): void {
    this.load(0);
  }

  apply(status: string): void {
    this.status = (status || null) as ReviewStatus | null;
    this.load(0);
  }

  load(page: number): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.forModeration(this.status, page).pipe(finalize(() => this.loading.set(false))).subscribe({
      next: (p) => {
        this.reviews.set(p.content);
        this.page.set(p.page);
        this.totalPages.set(p.totalPages);
      },
      error: (err) => this.error.set(toApiError(err).message),
    });
  }

  set(review: AdminReview, status: ReviewStatus, reason: string): void {
    this.busy.set(review.id);
    this.api.setVisibility(review.id, status, reason.trim() || null).pipe(finalize(() => this.busy.set(null))).subscribe({
      next: () => this.reviews.update((list) => list.map((r) => (r.id === review.id
        ? { ...r, status, hiddenReason: status === 'HIDDEN' ? reason.trim() || null : null } : r))),
      error: (err) => this.error.set(toApiError(err).message),
    });
  }
}
