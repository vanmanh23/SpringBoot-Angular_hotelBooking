package com.stayhotel.booking.catalog;

import java.util.Optional;
import java.util.UUID;

/** Read-only port to hotel-service. Implementations must never return non-public hotels or room types. */
public interface HotelCatalog {

    /**
     * @return the hotel if it is ACTIVE, empty if it does not exist or is not public
     * @throws CatalogUnavailableException if hotel-service cannot be reached or answers unexpectedly
     */
    Optional<HotelSnapshot> findActiveHotel(UUID hotelId);

    class CatalogUnavailableException extends RuntimeException {
        public CatalogUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
