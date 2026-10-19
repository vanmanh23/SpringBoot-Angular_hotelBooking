import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../config/api.config';
import { Page } from '../http/page';

export type ReviewStatus = 'VISIBLE' | 'HIDDEN';

/** Authors are always shown as "Verified guest" (no personal data in reviews). */
export interface Review {
  id: string;
  hotelId: string;
  bookingId: string;
  rating: number;
  comment: string | null;
  status: ReviewStatus;
  author: string;
  mine: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface AdminReview {
  id: string;
  hotelId: string;
  bookingId: string;
  rating: number;
  comment: string | null;
  status: ReviewStatus;
  hiddenReason: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ReviewEligibility {
  eligible: boolean;
  reviewId: string | null;
}

@Injectable({ providedIn: 'root' })
export class ReviewApi {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);

  forHotel(hotelId: string, page: number, size = 5): Observable<Page<Review>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<Page<Review>>(`${this.base}/hotels/${encodeURIComponent(hotelId)}/reviews`, { params });
  }

  mine(): Observable<Review[]> {
    return this.http.get<Review[]>(`${this.base}/reviews/me`);
  }

  eligibility(bookingId: string): Observable<ReviewEligibility> {
    return this.http.get<ReviewEligibility>(`${this.base}/reviews/eligibility/${encodeURIComponent(bookingId)}`);
  }

  create(bookingId: string, rating: number, comment: string | null): Observable<Review> {
    return this.http.post<Review>(`${this.base}/reviews`, { bookingId, rating, comment });
  }

  update(id: string, rating: number, comment: string | null): Observable<Review> {
    return this.http.put<Review>(`${this.base}/reviews/${encodeURIComponent(id)}`, { rating, comment });
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/reviews/${encodeURIComponent(id)}`);
  }

  // ---- admin
  forModeration(status: ReviewStatus | null, page: number, size = 20): Observable<Page<AdminReview>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (status) params = params.set('status', status);
    return this.http.get<Page<AdminReview>>(`${this.base}/admin/reviews`, { params });
  }

  setVisibility(id: string, status: ReviewStatus, reason: string | null): Observable<Review> {
    return this.http.patch<Review>(`${this.base}/admin/reviews/${encodeURIComponent(id)}/visibility`, { status, reason });
  }
}
