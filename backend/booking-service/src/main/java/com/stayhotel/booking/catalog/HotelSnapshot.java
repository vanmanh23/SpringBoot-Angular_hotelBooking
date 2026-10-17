package com.stayhotel.booking.catalog;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Trusted catalogue data needed to validate and price a booking, read from hotel-service at request time.
 * Only ACTIVE hotels and their ACTIVE room types are ever returned.
 */
public record HotelSnapshot(UUID id, String name, String timeZone, LocalTime checkInTime, BigDecimal taxRatePercent,
                            BigDecimal serviceFeePercent, List<RoomTypeSnapshot> roomTypes) {

    public Optional<RoomTypeSnapshot> roomType(UUID roomTypeId) {
        return roomTypes.stream().filter(rt -> rt.id().equals(roomTypeId)).findFirst();
    }

    public record RoomTypeSnapshot(UUID id, String name, int capacity, BigDecimal basePrice, String currency,
                                   int totalUnits) {
    }
}
