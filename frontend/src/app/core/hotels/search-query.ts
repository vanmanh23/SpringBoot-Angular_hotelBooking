import { ParamMap, Params } from '@angular/router';
import { HotelSearchQuery, HotelSort } from './hotel.models';

const SORTS: HotelSort[] = ['recommended', 'price_asc', 'price_desc', 'rating_desc', 'name_asc'];
const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** URL -> typed query. Unparseable values are dropped instead of being sent to the backend. */
export function parseSearchQuery(map: ParamMap): HotelSearchQuery {
  const int = (key: string, min: number, max: number) => {
    const raw = map.get(key);
    if (raw === null || raw.trim() === '') return undefined;
    const n = Number(raw);
    return Number.isInteger(n) && n >= min && n <= max ? n : undefined;
  };
  const text = (key: string) => {
    const v = map.get(key)?.trim();
    return v ? v.slice(0, 100) : undefined;
  };
  const date = (key: string) => {
    const v = map.get(key);
    return v && DATE.test(v) ? v : undefined;
  };
  const list = (key: string) =>
    (map.get(key) ?? '')
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean);

  const sort = map.get('sort') as HotelSort | null;
  const checkIn = date('checkIn');
  const checkOut = date('checkOut');
  const bothDates = checkIn && checkOut;
  return {
    city: text('city'),
    q: text('q'),
    checkIn: bothDates ? checkIn : undefined,
    checkOut: bothDates ? checkOut : undefined,
    guests: int('guests', 1, 40),
    rooms: int('rooms', 1, 10),
    minPrice: int('minPrice', 0, 1_000_000_000),
    maxPrice: int('maxPrice', 0, 1_000_000_000),
    stars: list('stars')
      .map(Number)
      .filter((n) => Number.isInteger(n) && n >= 1 && n <= 5),
    amenities: list('amenities').filter((c) => /^[A-Z][A-Z0-9_]{1,39}$/.test(c)),
    sort: sort && SORTS.includes(sort) ? sort : undefined,
    page: int('page', 0, 10_000),
  };
}

/** Typed query -> URL params (empty values removed). */
export function toQueryParams(query: HotelSearchQuery): Params {
  const params: Params = {};
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    if (Array.isArray(value)) {
      if (value.length) params[key] = value.join(',');
    } else {
      params[key] = value;
    }
  }
  return params;
}
