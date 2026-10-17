package com.stayhotel.booking;

import com.stayhotel.common.error.BusinessException;
import org.springframework.http.HttpStatus;

/** Error codes of booking-service (documented in API_CONTRACT.md, M3). */
public final class BookingErrors {

    private BookingErrors() {
    }

    public static BusinessException bookingNotFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "Booking not found");
    }

    public static BusinessException hotelNotAvailable() {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "HOTEL_NOT_AVAILABLE", "This hotel is not available for booking");
    }

    public static BusinessException roomTypeNotAvailable() {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "ROOM_TYPE_NOT_AVAILABLE",
                "One of the selected room types is not available at this hotel");
    }

    public static BusinessException capacityExceeded() {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "CAPACITY_EXCEEDED",
                "The selected rooms cannot host this number of guests");
    }

    public static BusinessException soldOut() {
        return new BusinessException(HttpStatus.CONFLICT, "ROOM_SOLD_OUT",
                "Not enough rooms left for the selected dates. Please choose other dates or rooms.");
    }

    public static BusinessException idempotencyKeyReused() {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                "This Idempotency-Key was already used for a different request");
    }

    public static BusinessException invalidTransition(Object from, Object to) {
        return new BusinessException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION",
                "Booking cannot change from " + from + " to " + to);
    }

    public static BusinessException cancellationNotAllowed() {
        return new BusinessException(HttpStatus.CONFLICT, "CANCELLATION_NOT_ALLOWED",
                "The check-in time has passed; this booking can no longer be cancelled online");
    }

    public static BusinessException catalogUnavailable() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "HOTEL_SERVICE_UNAVAILABLE",
                "Hotel information is temporarily unavailable. Please try again shortly.");
    }
}
