import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { User, UserStatus, Role } from '../auth/auth.models';
import { BookingStatus } from '../bookings/booking.models';
import { API_BASE_URL } from '../config/api.config';
import { Page } from '../http/page';
import { AmenityRef, HotelImage, RoomType } from '../hotels/hotel.models';
import { Payment, PaymentStatus } from '../payments/payment-api';

export type HotelStatus = 'DRAFT' | 'ACTIVE' | 'INACTIVE' | 'SUSPENDED';

export interface ManagedHotelSummary {
  id: string;
  name: string;
  city: string;
  countryCode: string;
  starRating: number | null;
  status: HotelStatus;
  approvedAt: string | null;
  version: number;
}

export interface ManagedHotel {
  id: string;
  name: string;
  description: string | null;
  address: string;
  city: string;
  countryCode: string;
  starRating: number | null;
  timeZone: string;
  checkInTime: string;
  checkOutTime: string;
  cancellationPolicy: string | null;
  taxRatePercent: number;
  serviceFeePercent: number;
  avgRating: number | null;
  reviewCount: number;
  images: HotelImage[];
  amenities: AmenityRef[];
  roomTypes: (RoomType & { version: number })[];
  status: HotelStatus;
  approvedAt: string | null;
  managerIds: string[];
  version: number;
}

export interface HotelRequest {
  name: string;
  description: string | null;
  address: string;
  city: string;
  countryCode: string;
  starRating: number | null;
  timeZone: string | null;
  checkInTime: string | null;
  checkOutTime: string | null;
  cancellationPolicy: string | null;
  taxRatePercent: number | null;
  serviceFeePercent: number | null;
  version?: number;
}

export interface RoomTypeRequest {
  name: string;
  description: string | null;
  capacity: number;
  bedDescription: string | null;
  basePrice: number;
  totalUnits: number;
  version?: number;
}

/** Staff view of a booking: contact name for the front desk, never payment details. */
export interface ManagedBooking {
  id: string;
  bookingCode: string;
  status: BookingStatus;
  hotelId: string;
  hotelName: string;
  checkInDate: string;
  checkOutDate: string;
  nights: number;
  adults: number;
  children: number;
  contactName: string;
  totalAmount: number;
  currency: string;
  refundStatus: 'NONE' | 'PENDING' | 'REFUNDED' | 'FAILED';
  createdAt: string;
}

function pageParams(page: number, size: number, extra: Record<string, string | number | boolean | null | undefined>): HttpParams {
  let params = new HttpParams().set('page', page).set('size', size);
  for (const [k, v] of Object.entries(extra)) {
    if (v !== null && v !== undefined && v !== '') params = params.set(k, String(v));
  }
  return params;
}

const id = encodeURIComponent;

/** Hotel management (assigned managers and admins). Authorization is enforced by hotel-service (D-030). */
@Injectable({ providedIn: 'root' })
export class ManageApi {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);

  hotels(status: HotelStatus | null, q: string | null, page: number, size = 20): Observable<Page<ManagedHotelSummary>> {
    return this.http.get<Page<ManagedHotelSummary>>(`${this.base}/manage/hotels`, { params: pageParams(page, size, { status, q }) });
  }

  hotel(hotelId: string): Observable<ManagedHotel> {
    return this.http.get<ManagedHotel>(`${this.base}/manage/hotels/${id(hotelId)}`);
  }

  createHotel(request: HotelRequest): Observable<ManagedHotel> {
    return this.http.post<ManagedHotel>(`${this.base}/hotels`, request);
  }

  updateHotel(hotelId: string, request: HotelRequest): Observable<ManagedHotel> {
    return this.http.put<ManagedHotel>(`${this.base}/hotels/${id(hotelId)}`, request);
  }

  setStatus(hotelId: string, status: HotelStatus): Observable<ManagedHotel> {
    return this.http.patch<ManagedHotel>(`${this.base}/hotels/${id(hotelId)}/status`, { status });
  }

  setAmenities(hotelId: string, codes: string[]): Observable<ManagedHotel> {
    return this.http.put<ManagedHotel>(`${this.base}/hotels/${id(hotelId)}/amenities`, { codes });
  }

  addImage(hotelId: string, url: string, caption: string | null): Observable<HotelImage> {
    return this.http.post<HotelImage>(`${this.base}/hotels/${id(hotelId)}/images`, { url, caption });
  }

  deleteImage(hotelId: string, imageId: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/hotels/${id(hotelId)}/images/${id(imageId)}`);
  }

  addRoomType(hotelId: string, request: RoomTypeRequest): Observable<RoomType> {
    return this.http.post<RoomType>(`${this.base}/hotels/${id(hotelId)}/room-types`, request);
  }

  updateRoomType(roomTypeId: string, request: RoomTypeRequest): Observable<RoomType> {
    return this.http.put<RoomType>(`${this.base}/room-types/${id(roomTypeId)}`, request);
  }

  setRoomTypeStatus(roomTypeId: string, status: 'ACTIVE' | 'INACTIVE'): Observable<RoomType> {
    return this.http.patch<RoomType>(`${this.base}/room-types/${id(roomTypeId)}/status`, { status });
  }

  bookings(hotelId: string, filter: { status?: BookingStatus | null; from?: string | null; to?: string | null },
           page: number, size = 20): Observable<Page<ManagedBooking>> {
    return this.http.get<Page<ManagedBooking>>(`${this.base}/manage/hotels/${id(hotelId)}/bookings`,
      { params: pageParams(page, size, filter) });
  }
}

/** Administrator-only endpoints. The UI hides them for other roles; the backend returns 403 regardless. */
@Injectable({ providedIn: 'root' })
export class AdminApi {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);

  users(q: string | null, status: UserStatus | null, page: number, size = 20): Observable<Page<User>> {
    return this.http.get<Page<User>>(`${this.base}/admin/users`, { params: pageParams(page, size, { q, status }) });
  }

  setUserStatus(userId: string, status: UserStatus, reason: string | null): Observable<User> {
    return this.http.patch<User>(`${this.base}/admin/users/${id(userId)}/status`, { status, reason });
  }

  setUserRoles(userId: string, roles: Role[]): Observable<User> {
    return this.http.put<User>(`${this.base}/admin/users/${id(userId)}/roles`, { roles });
  }

  addManager(hotelId: string, userId: string): Observable<string[]> {
    return this.http.post<string[]>(`${this.base}/hotels/${id(hotelId)}/managers`, { userId });
  }

  removeManager(hotelId: string, userId: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/hotels/${id(hotelId)}/managers/${id(userId)}`);
  }

  bookings(filter: { status?: BookingStatus | null; hotelId?: string | null; code?: string | null },
           page: number, size = 20): Observable<Page<ManagedBooking>> {
    return this.http.get<Page<ManagedBooking>>(`${this.base}/admin/bookings`, { params: pageParams(page, size, filter) });
  }

  payments(filter: { status?: PaymentStatus | null; needsReview?: boolean | null }, page: number, size = 20): Observable<Page<Payment>> {
    return this.http.get<Page<Payment>>(`${this.base}/admin/payments`, { params: pageParams(page, size, filter) });
  }

  refund(paymentId: string, reason: string): Observable<Payment> {
    return this.http.post<Payment>(`${this.base}/payments/${id(paymentId)}/refund`, { reason });
  }
}
