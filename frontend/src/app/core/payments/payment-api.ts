import { HttpClient } from '@angular/common/http';
import { inject, Injectable, InjectionToken } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../config/api.config';

export type PaymentStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'REFUND_PENDING' | 'REFUNDED';

export interface Payment {
  id: string;
  bookingId: string;
  amount: number;
  currency: string;
  provider: string;
  status: PaymentStatus;
  checkoutUrl: string | null;
  failureReason: string | null;
  needsReview: boolean;
  createdAt: string;
  succeededAt: string | null;
  refund: { id: string; amount: number; status: 'PENDING' | 'SUCCEEDED' | 'FAILED'; createdAt: string; completedAt: string | null } | null;
}

@Injectable({ providedIn: 'root' })
export class PaymentApi {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);

  /** Only the booking id is sent: the amount is always taken from the booking by the backend (BR-22). */
  start(bookingId: string, idempotencyKey: string): Observable<Payment> {
    return this.http.post<Payment>(`${this.base}/payments`, { bookingId }, { headers: { 'Idempotency-Key': idempotencyKey } });
  }

  forBooking(bookingId: string): Observable<Payment[]> {
    return this.http.get<Payment[]>(`${this.base}/payments/booking/${encodeURIComponent(bookingId)}`);
  }
}

/** Full-page navigation to the provider; a token so tests can observe it instead of leaving the page. */
export const REDIRECT_TO = new InjectionToken<(url: string) => void>('REDIRECT_TO', {
  providedIn: 'root',
  factory: () => (url: string) => window.location.assign(url),
});

/** Only follow checkout URLs that point at our own origin's provider path (no open redirect via the API). */
export function isTrustedCheckoutUrl(url: string | null, origin = window.location.origin): url is string {
  if (!url) return false;
  try {
    const parsed = new URL(url, origin);
    return parsed.origin === origin && parsed.pathname.startsWith('/sandbox-gateway/checkout/');
  } catch {
    return false;
  }
}
