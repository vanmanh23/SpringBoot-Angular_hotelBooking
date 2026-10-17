export type HotelSort = 'recommended' | 'price_asc' | 'price_desc' | 'rating_desc' | 'name_asc';
export type AmenityCategory = 'HOTEL' | 'ROOM';

export interface Amenity {
  id: string;
  code: string;
  name: string;
  category: AmenityCategory;
}

export interface AmenityRef {
  code: string;
  name: string;
}

export interface HotelSummary {
  id: string;
  name: string;
  city: string;
  address: string;
  countryCode: string;
  starRating: number | null;
  avgRating: number | null;
  reviewCount: number;
  thumbnailUrl: string | null;
  /** Cheapest suitable room type per night; advisory only (BR-08). */
  fromPrice: number;
  currency: string;
  amenities: string[];
}

export interface RoomType {
  id: string;
  hotelId: string;
  name: string;
  description: string | null;
  capacity: number;
  bedDescription: string | null;
  basePrice: number;
  currency: string;
  totalUnits: number;
  status: 'ACTIVE' | 'INACTIVE';
  amenities: AmenityRef[];
}

export interface HotelImage {
  id: string;
  url: string;
  caption: string | null;
}

export interface HotelDetail {
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
  roomTypes: RoomType[];
}

/** Search criteria as they appear in the URL (all optional except defaults applied by the backend). */
export interface HotelSearchQuery {
  city?: string;
  q?: string;
  checkIn?: string;
  checkOut?: string;
  guests?: number;
  rooms?: number;
  minPrice?: number;
  maxPrice?: number;
  stars?: number[];
  amenities?: string[];
  sort?: HotelSort;
  page?: number;
  size?: number;
}
