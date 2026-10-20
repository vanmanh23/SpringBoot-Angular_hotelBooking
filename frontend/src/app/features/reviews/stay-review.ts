import { ChangeDetectionStrategy, Component, inject, input, OnInit, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { forkJoin, of, switchMap } from 'rxjs';
import { toApiError } from '../../core/http/api-error';
import { Review, ReviewApi } from '../../core/reviews/review-api';

/**
 * Review of one completed stay: write it, or edit/delete the existing one. Eligibility is decided by review-service
 * (completed stay of this user, D-027); this component only reflects it.
 */
@Component({
  selector: 'app-stay-review',
  imports: [ReactiveFormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="card" aria-labelledby="stay-review-title">
      <h2 id="stay-review-title">Your review</h2>
      @if (loading()) {
        <p class="muted" role="status">Checking review…</p>
      } @else if (review() && !editing()) {
        @if (review(); as r) {
          <p><span class="stars" [attr.aria-label]="r.rating + ' out of 5'">{{ '★'.repeat(r.rating) }}{{ '☆'.repeat(5 - r.rating) }}</span>
            @if (r.status === 'HIDDEN') { <span class="muted"> · hidden by moderation</span> }</p>
          @if (r.comment) { <p class="comment">{{ r.comment }}</p> }
          <div class="actions">
            <button class="btn btn-secondary" type="button" (click)="startEdit(r)">Edit</button>
            <button class="btn btn-secondary" type="button" (click)="remove(r)" [disabled]="saving()">Delete</button>
          </div>
        }
      } @else if (eligible() || editing()) {
        <form [formGroup]="form" (ngSubmit)="save()" novalidate>
          <fieldset class="rating">
            <legend>Rating</legend>
            @for (n of [1, 2, 3, 4, 5]; track n) {
              <label><input type="radio" formControlName="rating" [value]="n" /> {{ n }}★</label>
            }
          </fieldset>
          <div class="field">
            <label for="review-comment">Comment (optional)</label>
            <textarea id="review-comment" formControlName="comment" rows="4" maxlength="2000"></textarea>
          </div>
          <div class="actions">
            <button class="btn btn-primary" type="submit" [disabled]="saving() || form.invalid">
              {{ saving() ? 'Saving…' : review() ? 'Save changes' : 'Publish review' }}
            </button>
            @if (editing()) {
              <button class="btn btn-secondary" type="button" (click)="editing.set(false)">Cancel</button>
            }
          </div>
        </form>
      } @else {
        <p class="muted">Reviews open once your stay is completed.</p>
      }
      @if (error()) { <p class="alert alert-error" role="alert">{{ error() }}</p> }
    </section>
  `,
  styles: `
    :host { display: block; margin-bottom: 16px; }
    h2 { margin-top: 0; font-size: 1.1rem; }
    .stars { color: var(--color-text); letter-spacing: 1px; }
    .comment { white-space: pre-line; overflow-wrap: anywhere; }
    .rating { border: 0; padding: 0; margin: 0 0 12px; display: flex; gap: 12px; flex-wrap: wrap; }
    .rating legend { font-weight: 600; margin-bottom: 4px; }
    textarea { font: inherit; padding: 8px; border: 1px solid var(--color-border); border-radius: var(--radius); }
    .actions { display: flex; gap: 8px; flex-wrap: wrap; }
  `,
})
export class StayReview implements OnInit {
  private readonly api = inject(ReviewApi);

  readonly bookingId = input.required<string>();

  protected readonly loading = signal(true);
  protected readonly eligible = signal(false);
  protected readonly review = signal<Review | null>(null);
  protected readonly editing = signal(false);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = new FormGroup({
    rating: new FormControl<number | null>(null, [Validators.required, Validators.min(1), Validators.max(5)]),
    comment: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(2000)] }),
  });

  ngOnInit(): void {
    this.api
      .eligibility(this.bookingId())
      .pipe(switchMap((e) => forkJoin({ e: of(e), mine: e.reviewId ? this.api.mine() : of([] as Review[]) })))
      .subscribe({
        next: ({ e, mine }) => {
          this.eligible.set(e.eligible);
          this.review.set(mine.find((r) => r.id === e.reviewId) ?? null);
          this.loading.set(false);
        },
        error: (err) => {
          this.loading.set(false);
          this.error.set(toApiError(err).message);
        },
      });
  }

  startEdit(r: Review): void {
    this.form.setValue({ rating: r.rating, comment: r.comment ?? '' });
    this.editing.set(true);
  }

  save(): void {
    if (this.form.invalid || this.saving()) return;
    const rating = this.form.controls.rating.value!;
    const comment = this.form.controls.comment.value.trim() || null;
    const existing = this.review();
    this.saving.set(true);
    this.error.set(null);
    (existing ? this.api.update(existing.id, rating, comment) : this.api.create(this.bookingId(), rating, comment)).subscribe({
      next: (r) => {
        this.saving.set(false);
        this.review.set(r);
        this.eligible.set(false);
        this.editing.set(false);
      },
      error: (err) => {
        this.saving.set(false);
        const e = toApiError(err);
        this.error.set(e.code === 'REVIEW_EXISTS' ? 'You have already reviewed this stay.' : e.message);
      },
    });
  }

  remove(r: Review): void {
    this.saving.set(true);
    this.api.delete(r.id).subscribe({
      next: () => {
        this.saving.set(false);
        this.review.set(null);
        this.eligible.set(true);
        this.form.reset({ rating: null, comment: '' });
      },
      error: (err) => {
        this.saving.set(false);
        this.error.set(toApiError(err).message);
      },
    });
  }
}
