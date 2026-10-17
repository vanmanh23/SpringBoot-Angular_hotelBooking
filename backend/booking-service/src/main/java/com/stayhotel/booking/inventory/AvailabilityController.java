package com.stayhotel.booking.inventory;

import com.stayhotel.booking.BookingErrors;
import com.stayhotel.booking.catalog.HotelCatalog;
import com.stayhotel.booking.catalog.HotelSnapshot;
import com.stayhotel.common.error.BusinessException;
import com.stayhotel.common.error.FieldValidationException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Public, advisory availability for a stay (BR-08): units left per active room type = totalUnits − the highest
 * reservation count over the nights. It is not a hold; booking re-checks atomically (BR-09).
 */
@RestController
class AvailabilityController {

    private final HotelCatalog catalog;
    private final InventoryRepository inventory;

    AvailabilityController(HotelCatalog catalog, InventoryRepository inventory) {
        this.catalog = catalog;
        this.inventory = inventory;
    }

    @GetMapping("/api/v1/availability")
    AvailabilityResponse availability(@RequestParam UUID hotelId,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut) {
        if (!checkOut.isAfter(checkIn)) {
            throw new FieldValidationException("checkOut", "must be after checkIn");
        }
        if (ChronoUnit.DAYS.between(checkIn, checkOut) > 30) {
            throw new FieldValidationException("checkOut", "stay must be at most 30 nights");
        }
        HotelSnapshot hotel;
        try {
            hotel = catalog.findActiveHotel(hotelId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "HOTEL_NOT_AVAILABLE", "Hotel not found"));
        } catch (HotelCatalog.CatalogUnavailableException e) {
            throw BookingErrors.catalogUnavailable();
        }
        Map<UUID, Integer> reserved = inventory.maxReserved(
                hotel.roomTypes().stream().map(HotelSnapshot.RoomTypeSnapshot::id).toList(), checkIn, checkOut);
        List<RoomTypeAvailability> rooms = hotel.roomTypes().stream()
                .map(rt -> new RoomTypeAvailability(rt.id(), rt.totalUnits(),
                        Math.max(0, rt.totalUnits() - reserved.getOrDefault(rt.id(), 0))))
                .toList();
        return new AvailabilityResponse(hotelId, checkIn, checkOut, rooms);
    }

    record AvailabilityResponse(UUID hotelId, LocalDate checkIn, LocalDate checkOut, List<RoomTypeAvailability> roomTypes) {
    }

    record RoomTypeAvailability(UUID roomTypeId, int totalUnits, int availableUnits) {
    }
}
