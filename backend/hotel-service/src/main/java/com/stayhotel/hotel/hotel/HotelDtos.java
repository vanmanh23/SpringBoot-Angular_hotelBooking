package com.stayhotel.hotel.hotel;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.stayhotel.hotel.amenity.AmenityDtos.AmenityRef;
import com.stayhotel.hotel.roomtype.RoomTypeDtos.RoomTypeResponse;
import com.stayhotel.hotel.support.IanaTimeZone;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public final class HotelDtos {

    public static final String DEFAULT_TIME_ZONE = "Asia/Ho_Chi_Minh";
    private static final LocalTime DEFAULT_CHECK_IN = LocalTime.of(14, 0);
    private static final LocalTime DEFAULT_CHECK_OUT = LocalTime.of(12, 0);
    /** Owner-approved defaults (DECISIONS D-020): VAT 8 %, service fee 5 %. */
    public static final BigDecimal DEFAULT_TAX_RATE = new BigDecimal("8.00");
    public static final BigDecimal DEFAULT_SERVICE_FEE = new BigDecimal("5.00");

    private HotelDtos() {
    }

    /** {@code version} is required on update (optimistic locking) and ignored on create. */
    public record HotelRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 5000) String description,
            @NotBlank @Size(max = 500) String address,
            @NotBlank @Size(max = 100) String city,
            @NotBlank @Pattern(regexp = "^[A-Z]{2}$") String countryCode,
            @Min(1) @Max(5) Integer starRating,
            @IanaTimeZone String timeZone,
            @JsonFormat(pattern = "HH:mm") LocalTime checkInTime,
            @JsonFormat(pattern = "HH:mm") LocalTime checkOutTime,
            @Size(max = 2000) String cancellationPolicy,
            @DecimalMin("0") @DecimalMax("30") @Digits(integer = 2, fraction = 2) BigDecimal taxRatePercent,
            @DecimalMin("0") @DecimalMax("30") @Digits(integer = 2, fraction = 2) BigDecimal serviceFeePercent,
            Long version) {

        HotelDetails toDetails() {
            return new HotelDetails(name, description, address, city, countryCode, starRating,
                    timeZone == null || timeZone.isBlank() ? DEFAULT_TIME_ZONE : timeZone,
                    checkInTime == null ? DEFAULT_CHECK_IN : checkInTime,
                    checkOutTime == null ? DEFAULT_CHECK_OUT : checkOutTime,
                    cancellationPolicy,
                    taxRatePercent == null ? DEFAULT_TAX_RATE : taxRatePercent,
                    serviceFeePercent == null ? DEFAULT_SERVICE_FEE : serviceFeePercent);
        }
    }

    public record StatusRequest(@NotNull HotelStatus status) {
    }

    public record ImageRequest(@NotBlank @Size(max = 1000) @Pattern(regexp = "^https://\\S+$",
            message = "must be an https:// URL") String url, @Size(max = 200) String caption) {
    }

    public record ManagerRequest(@NotNull UUID userId) {
    }

    public record ImageResponse(UUID id, String url, String caption) {
        static ImageResponse from(HotelImage image) {
            return new ImageResponse(image.getId(), image.getUrl(), image.getCaption());
        }
    }

    /** Public detail; room types are the active ones only. */
    public record HotelDetailResponse(UUID id, String name, String description, String address, String city,
                                      String countryCode, Short starRating, String timeZone,
                                      @JsonFormat(pattern = "HH:mm") LocalTime checkInTime,
                                      @JsonFormat(pattern = "HH:mm") LocalTime checkOutTime,
                                      String cancellationPolicy, BigDecimal taxRatePercent,
                                      BigDecimal serviceFeePercent, BigDecimal avgRating, int reviewCount,
                                      List<ImageResponse> images, List<AmenityRef> amenities,
                                      List<RoomTypeResponse> roomTypes) {
    }

    /** Management view: adds lifecycle data, assignments and every room type regardless of status. */
    public record ManagedHotelResponse(UUID id, String name, String description, String address, String city,
                                       String countryCode, Short starRating, String timeZone,
                                       @JsonFormat(pattern = "HH:mm") LocalTime checkInTime,
                                       @JsonFormat(pattern = "HH:mm") LocalTime checkOutTime,
                                       String cancellationPolicy, BigDecimal taxRatePercent,
                                       BigDecimal serviceFeePercent, BigDecimal avgRating, int reviewCount,
                                       List<ImageResponse> images, List<AmenityRef> amenities,
                                       List<RoomTypeResponse> roomTypes, HotelStatus status, Instant approvedAt,
                                       List<UUID> managerIds, long version) {
    }
}
