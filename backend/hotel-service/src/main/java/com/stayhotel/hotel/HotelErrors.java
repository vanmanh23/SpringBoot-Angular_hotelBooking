package com.stayhotel.hotel;

import com.stayhotel.common.error.BusinessException;
import com.stayhotel.common.error.DataAccessExceptionHandler;
import com.stayhotel.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** Error codes of hotel-service (documented in API_CONTRACT.md, M2). */
public final class HotelErrors {

    private HotelErrors() {
    }

    public static BusinessException hotelNotFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "HOTEL_NOT_FOUND", "Hotel not found");
    }

    public static BusinessException roomTypeNotFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "ROOM_TYPE_NOT_FOUND", "Room type not found");
    }

    public static BusinessException imageNotFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "IMAGE_NOT_FOUND", "Image not found");
    }

    public static BusinessException managerNotFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "MANAGER_NOT_FOUND", "Manager is not assigned to this hotel");
    }

    public static BusinessException forbidden() {
        return new BusinessException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "Access denied");
    }

    public static BusinessException invalidTransition(Object from, Object to) {
        return new BusinessException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION",
                "Status cannot change from " + from + " to " + to);
    }

    public static BusinessException staleVersion() {
        return new BusinessException(HttpStatus.CONFLICT, DataAccessExceptionHandler.CONCURRENT_MODIFICATION,
                "The resource was modified by someone else. Reload and try again.");
    }

    public static BusinessException unknownAmenity(String code) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "UNKNOWN_AMENITY", "Unknown or misplaced amenity: " + code);
    }

    public static BusinessException amenityCodeTaken() {
        return new BusinessException(HttpStatus.CONFLICT, "AMENITY_CODE_TAKEN", "Amenity code already exists");
    }

    public static BusinessException imageLimitReached(int max) {
        return new BusinessException(HttpStatus.CONFLICT, "IMAGE_LIMIT_REACHED", "A hotel can have at most " + max + " images");
    }

    public static BusinessException roomTypeNameTaken() {
        return new BusinessException(HttpStatus.CONFLICT, "ROOM_TYPE_NAME_TAKEN",
                "This hotel already has a room type with that name");
    }
}
