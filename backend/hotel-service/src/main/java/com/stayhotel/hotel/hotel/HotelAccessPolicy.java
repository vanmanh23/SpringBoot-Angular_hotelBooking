package com.stayhotel.hotel.hotel;

import com.stayhotel.common.security.CurrentUser;
import com.stayhotel.hotel.HotelErrors;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resource-level authorization (BR-30, anti-IDOR): ADMIN manages every hotel; a HOTEL_MANAGER only hotels they
 * are assigned to. The role must be present in the token AND the assignment must exist in hotel_managers.
 */
@Component
public class HotelAccessPolicy {

    private final HotelManagerRepository managers;

    HotelAccessPolicy(HotelManagerRepository managers) {
        this.managers = managers;
    }

    public boolean canManage(CurrentUser user, UUID hotelId) {
        return user.isAdmin()
                || user.hasRole(CurrentUser.ROLE_HOTEL_MANAGER) && managers.existsByIdHotelIdAndIdUserId(hotelId, user.id());
    }

    public void requireManage(CurrentUser user, UUID hotelId) {
        if (!canManage(user, hotelId)) {
            throw HotelErrors.forbidden();
        }
    }

    public void requireCreator(CurrentUser user) {
        if (!user.isAdmin() && !user.hasRole(CurrentUser.ROLE_HOTEL_MANAGER)) {
            throw HotelErrors.forbidden();
        }
    }
}
