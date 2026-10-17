package com.stayhotel.hotel.roomtype;

import com.stayhotel.hotel.amenity.AmenityDtos.AmenityRef;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

public final class RoomTypeDtos {

    private RoomTypeDtos() {
    }

    /** {@code version} is required on update (optimistic locking) and ignored on create. */
    public record RoomTypeRequest(
            @NotBlank @Size(max = 150) String name,
            @Size(max = 2000) String description,
            @NotNull @Min(1) @Max(20) Integer capacity,
            @Size(max = 200) String bedDescription,
            @NotNull @DecimalMin("1000") @DecimalMax("1000000000") @Digits(integer = 10, fraction = 0) BigDecimal basePrice,
            @NotNull @Min(0) @Max(1000) Integer totalUnits,
            Long version) {
    }

    public record StatusRequest(@NotNull RoomTypeStatus status) {
    }

    public record RoomTypeResponse(UUID id, UUID hotelId, String name, String description, int capacity,
                                   String bedDescription, BigDecimal basePrice, String currency, int totalUnits,
                                   RoomTypeStatus status, List<AmenityRef> amenities, long version) {

        public static RoomTypeResponse from(RoomType rt) {
            return new RoomTypeResponse(rt.getId(), rt.getHotelId(), rt.getName(), rt.getDescription(),
                    rt.getCapacity(), rt.getBedDescription(), rt.getBasePrice().setScale(0, RoundingMode.UNNECESSARY),
                    rt.getCurrencyCode(), rt.getTotalUnits(), rt.getStatus(), AmenityRef.sorted(rt.getAmenities()),
                    rt.getVersion());
        }
    }
}
