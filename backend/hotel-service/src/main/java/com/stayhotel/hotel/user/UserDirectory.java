package com.stayhotel.hotel.user;

import java.util.UUID;

/** Read access to auth-service user data, always on behalf of the caller (their own token). */
public interface UserDirectory {

    /**
     * Fails with 404 USER_NOT_FOUND, 422 USER_NOT_HOTEL_MANAGER (missing role or not ACTIVE) or 503 when auth-service
     * is unavailable (R-M5-1).
     */
    void requireActiveHotelManager(UUID userId, String callerBearerToken);
}
