import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../config/api.config';
import { Page } from '../http/page';
import { Availability, Booking, BookingRequest, BookingStatus, BookingSummary, Quote } from './booking.models';

@Injectable({ providedIn: 'root' })
export class BookingApi {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);

  availability(hotelId: string, checkIn: string, checkOut: string): Observable<Availability> {
    const params = new HttpParams().set('hotelId', hotelId).set('checkIn', checkIn).set('checkOut', checkOut);
    return this.http.get<Availability>(`${this.base}/availability`, { params });
  }

  quote(request: BookingRequest): Observable<Quote> {
    return this.http.post<Quote>(`${this.base}/bookings/quote`, request);
  }

  /**
   * The same idempotency key must be reused when retrying the SAME request (network error, double click), so the
   * backend returns the original booking instead of creating a second one (BR-21).
   */
  create(request: BookingRequest, idempotencyKey: string): Observable<Booking> {
    return this.http.post<Booking>(`${this.base}/bookings`, request, {
      headers: { 'Idempotency-Key': idempotencyKey },
    });
  }

  mine(status: BookingStatus | null, page: number, size = 10): Observable<Page<BookingSummary>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (status) params = params.set('status', status);
    return this.http.get<Page<BookingSummary>>(`${this.base}/bookings/me`, { params });
  }

  get(id: string): Observable<Booking> {
    return this.http.get<Booking>(`${this.base}/bookings/${encodeURIComponent(id)}`);
  }

  status(id: string): Observable<{ id: string; status: BookingStatus; expiresAt: string | null }> {
    return this.http.get<{ id: string; status: BookingStatus; expiresAt: string | null }>(
      `${this.base}/bookings/${encodeURIComponent(id)}/status`,
    );
  }

  cancel(id: string, reason: string | null): Observable<Booking> {
    return this.http.post<Booking>(`${this.base}/bookings/${encodeURIComponent(id)}/cancel`, { reason });
  }
}

/** Fresh key per distinct booking attempt. */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}
