export type BookingStatus = 'PENDING_PAYMENT' | 'CONFIRMED' | 'CANCELLED' | 'EXPIRED' | 'COMPLETED';

export interface BookingRoomLine {
  roomTypeId: string;
  quantity: number;
}

/** No amounts on purpose: the backend computes every price (BR-12). */
export interface BookingRequest {
  hotelId: string;
  checkInDate: string;
  checkOutDate: string;
  adults: number;
  children: number;
  rooms: BookingRoomLine[];
  contactName: string;
  contactEmail: string;
  contactPhone?: string | null;
  specialRequests?: string | null;
}

export interface PricedLine {
  roomTypeId: string;
  roomTypeName: string;
  quantity: number;
  unitPrice: number;
  nights: number;
  lineSubtotal: number;
}

export interface Quote {
  hotelId: string;
  hotelName: string;
  checkInDate: string;
  checkOutDate: string;
  nights: number;
  currency: string;
  lines: PricedLine[];
  subtotal: number;
  serviceFeePercent: number;
  feeAmount: number;
  taxRatePercent: number;
  taxAmount: number;
  discountAmount: number;
  totalAmount: number;
  available: boolean;
}

export interface BookingHistoryEntry {
  fromStatus: BookingStatus | null;
  toStatus: BookingStatus;
  reason: string | null;
  actor: string;
  at: string;
}

export interface Booking {
  id: string;
  bookingCode: string;
  status: BookingStatus;
  hotelId: string;
  hotelName: string;
  hotelTimeZone: string;
  checkInDate: string;
  checkOutDate: string;
  checkInTime: string;
  nights: number;
  adults: number;
  children: number;
  lines: PricedLine[];
  subtotal: number;
  serviceFeePercent: number;
  feeAmount: number;
  taxRatePercent: number;
  taxAmount: number;
  discountAmount: number;
  totalAmount: number;
  currency: string;
  contactName: string;
  contactEmail: string;
  contactPhone: string | null;
  specialRequests: string | null;
  expiresAt: string | null;
  confirmedAt: string | null;
  cancelledAt: string | null;
  cancelReason: string | null;
  refundEligible: boolean | null;
  refundStatus: 'NONE' | 'PENDING' | 'REFUNDED' | 'FAILED';
  freeCancellationUntil: string;
  createdAt: string;
  history: BookingHistoryEntry[];
}

export interface BookingSummary {
  id: string;
  bookingCode: string;
  status: BookingStatus;
  hotelId: string;
  hotelName: string;
  checkInDate: string;
  checkOutDate: string;
  nights: number;
  totalAmount: number;
  currency: string;
  expiresAt: string | null;
  createdAt: string;
}

export interface Availability {
  hotelId: string;
  checkIn: string;
  checkOut: string;
  roomTypes: { roomTypeId: string; totalUnits: number; availableUnits: number }[];
}

export const STATUS_LABELS: Record<BookingStatus, string> = {
  PENDING_PAYMENT: 'Awaiting payment',
  CONFIRMED: 'Confirmed',
  CANCELLED: 'Cancelled',
  EXPIRED: 'Expired',
  COMPLETED: 'Completed',
};
