import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../config/api.config';
import { Page } from '../http/page';
import { Amenity, AmenityCategory, HotelDetail, HotelSearchQuery, HotelSummary, RoomType } from './hotel.models';

@Injectable({ providedIn: 'root' })
export class HotelApi {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);

  search(query: HotelSearchQuery): Observable<Page<HotelSummary>> {
    return this.http.get<Page<HotelSummary>>(`${this.base}/hotels`, { params: toSearchParams(query) });
  }

  detail(id: string): Observable<HotelDetail> {
    return this.http.get<HotelDetail>(`${this.base}/hotels/${encodeURIComponent(id)}`);
  }

  roomTypes(id: string, guests: number, rooms: number): Observable<RoomType[]> {
    const params = new HttpParams().set('guests', guests).set('rooms', rooms);
    return this.http.get<RoomType[]>(`${this.base}/hotels/${encodeURIComponent(id)}/room-types`, { params });
  }

  amenities(category?: AmenityCategory): Observable<Amenity[]> {
    const params = category ? new HttpParams().set('category', category) : undefined;
    return this.http.get<Amenity[]>(`${this.base}/amenities`, { params });
  }
}

/** Builds query params, omitting empty values so URLs and requests stay minimal. */
export function toSearchParams(query: HotelSearchQuery): HttpParams {
  let params = new HttpParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    if (Array.isArray(value)) {
      if (value.length) params = params.set(key, value.join(','));
    } else {
      params = params.set(key, String(value));
    }
  }
  return params;
}
