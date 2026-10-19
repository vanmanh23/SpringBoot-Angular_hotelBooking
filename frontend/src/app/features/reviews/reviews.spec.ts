import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Review } from '../../core/reviews/review-api';
import { signInAs } from '../../testing/sign-in';
import { HotelReviews } from './hotel-reviews';
import { StayReview } from './stay-review';

const review = (id: string, over: Partial<Review> = {}): Review => ({
  id, hotelId: 'h1', bookingId: 'b-' + id, rating: 4, comment: 'Lovely <img src=x onerror=alert(1)>', status: 'VISIBLE',
  author: 'Verified guest', mine: false, createdAt: '2026-10-01T10:00:00Z', updatedAt: '2026-10-01T10:00:00Z', ...over,
});

describe('reviews', () => {
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  describe('HotelReviews', () => {
    it('shows the score and reviews as text, pages on demand, and offers moderation to nobody but admins', async () => {
      const fixture = TestBed.createComponent(HotelReviews);
      fixture.componentRef.setInput('hotelId', 'h1');
      fixture.componentRef.setInput('avgRating', 4.5);
      fixture.componentRef.setInput('reviewCount', 6);
      fixture.detectChanges();
      backend.expectOne('/api/v1/hotels/h1/reviews?page=0&size=5')
        .flush({ content: [review('r1')], page: 0, size: 5, totalElements: 6, totalPages: 2 });
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;

      expect(el.textContent).toContain('4.5 / 5 · 6 reviews');
      expect(el.textContent).toContain('Verified guest');
      expect(el.querySelector('.comment img')).toBeNull(); // interpolated, never parsed as HTML
      expect(el.textContent).not.toContain('Hide review');

      [...el.querySelectorAll('button')].find((b) => b.textContent?.includes('Show more'))!.click();
      backend.expectOne('/api/v1/hotels/h1/reviews?page=1&size=5')
        .flush({ content: [review('r2')], page: 1, size: 5, totalElements: 6, totalPages: 2 });
      fixture.detectChanges();
      expect(el.querySelectorAll('.review').length).toBe(2);
      expect(el.textContent).not.toContain('Show more');
    });

    it('lets an admin hide a review', async () => {
      await signInAs(backend, ['ADMIN']);
      const fixture = TestBed.createComponent(HotelReviews);
      fixture.componentRef.setInput('hotelId', 'h1');
      fixture.detectChanges();
      backend.expectOne((r) => r.url === '/api/v1/hotels/h1/reviews')
        .flush({ content: [review('r1')], page: 0, size: 5, totalElements: 1, totalPages: 1 });
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;

      [...el.querySelectorAll('button')].find((b) => b.textContent?.includes('Hide review'))!.click();
      const req = backend.expectOne('/api/v1/admin/reviews/r1/visibility');
      expect(req.request.method).toBe('PATCH');
      expect(req.request.body.status).toBe('HIDDEN');
      req.flush(review('r1', { status: 'HIDDEN' }));
      fixture.detectChanges();
      expect(el.querySelectorAll('.review').length).toBe(0);
    });
  });

  describe('StayReview', () => {
    function create() {
      const fixture = TestBed.createComponent(StayReview);
      fixture.componentRef.setInput('bookingId', 'b1');
      fixture.detectChanges();
      return fixture;
    }

    it('publishes a review for an eligible completed stay (only booking id, rating and comment are sent)', () => {
      const fixture = create();
      backend.expectOne('/api/v1/reviews/eligibility/b1').flush({ eligible: true, reviewId: null });
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;

      const submit = el.querySelector<HTMLButtonElement>('button[type=submit]')!;
      expect(submit.disabled).toBe(true); // rating required
      el.querySelectorAll<HTMLInputElement>('input[type=radio]')[4].click();
      const comment = el.querySelector('textarea')!;
      comment.value = '  Great stay  ';
      comment.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      submit.click();

      const req = backend.expectOne('/api/v1/reviews');
      expect(req.request.body).toEqual({ bookingId: 'b1', rating: 5, comment: 'Great stay' });
      req.flush(review('new', { rating: 5, comment: 'Great stay', mine: true }));
      fixture.detectChanges();
      expect(el.textContent).toContain('★★★★★');
      expect(el.textContent).toContain('Edit');
    });

    it('shows the existing review with edit and delete; a 409 from a double submit is explained', () => {
      const fixture = create();
      backend.expectOne('/api/v1/reviews/eligibility/b1').flush({ eligible: false, reviewId: 'r9' });
      backend.expectOne('/api/v1/reviews/me').flush([review('r9', { bookingId: 'b1', mine: true, rating: 3 })]);
      fixture.detectChanges();
      const el = fixture.nativeElement as HTMLElement;
      expect(el.textContent).toContain('★★★☆☆');

      [...el.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Edit')!.click();
      fixture.detectChanges();
      el.querySelector<HTMLButtonElement>('button[type=submit]')!.click();
      const req = backend.expectOne('/api/v1/reviews/r9');
      expect(req.request.method).toBe('PUT');
      req.flush({ status: 409, code: 'REVIEW_EXISTS', message: 'x', fieldErrors: [] }, { status: 409, statusText: 'Conflict' });
      fixture.detectChanges();
      expect(el.textContent).toContain('already reviewed');
    });

    it('explains that reviews open after the stay when not eligible', () => {
      const fixture = create();
      backend.expectOne('/api/v1/reviews/eligibility/b1').flush({ eligible: false, reviewId: null });
      fixture.detectChanges();
      expect((fixture.nativeElement as HTMLElement).textContent).toContain('Reviews open once your stay is completed');
    });
  });
});
