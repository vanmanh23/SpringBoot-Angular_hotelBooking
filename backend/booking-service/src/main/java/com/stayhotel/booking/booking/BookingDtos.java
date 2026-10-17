package com.stayhotel.booking.booking;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public final class BookingDtos {

    private BookingDtos() {
    }

    /** Carries no amounts on purpose: prices are computed by the backend only (BR-12). Unknown fields are ignored. */
    public record BookingRequest(
            @NotNull UUID hotelId,
            @NotNull LocalDate checkInDate,
            @NotNull LocalDate checkOutDate,
            @NotNull @Min(1) @Max(40) Integer adults,
            @NotNull @Min(0) @Max(20) Integer children,
            @NotEmpty @Size(max = 5) List<@Valid @NotNull RoomLine> rooms,
            @NotBlank @Size(max = 150) String contactName,
            @NotBlank @Email @Size(max = 255) String contactEmail,
            @Pattern(regexp = "^[0-9+() .-]{0,30}$") String contactPhone,
            @Size(max = 1000) String specialRequests) {
    }

    public record RoomLine(@NotNull UUID roomTypeId, @NotNull @Min(1) @Max(10) Integer quantity) {
    }

    public record CancelRequest(@Size(max = 300) String reason) {
    }

    public record QuoteLine(UUID roomTypeId, String roomTypeName, int quantity, BigDecimal unitPrice, int nights,
                            BigDecimal lineSubtotal) {
    }

    public record QuoteResponse(UUID hotelId, String hotelName, LocalDate checkInDate, LocalDate checkOutDate, int nights,
                                String currency, List<QuoteLine> lines, BigDecimal subtotal,
                                BigDecimal serviceFeePercent, BigDecimal feeAmount, BigDecimal taxRatePercent,
                                BigDecimal taxAmount, BigDecimal discountAmount, BigDecimal totalAmount,
                                boolean available) {
    }

    public record HistoryEntry(BookingStatus fromStatus, BookingStatus toStatus, String reason, String actor, Instant at) {
    }

    public record BookingResponse(UUID id, String bookingCode, BookingStatus status, UUID hotelId, String hotelName,
                                  String hotelTimeZone, LocalDate checkInDate, LocalDate checkOutDate,
                                  @JsonFormat(pattern = "HH:mm") LocalTime checkInTime, int nights, int adults,
                                  int children, List<QuoteLine> lines, BigDecimal subtotal,
                                  BigDecimal serviceFeePercent, BigDecimal feeAmount, BigDecimal taxRatePercent,
                                  BigDecimal taxAmount, BigDecimal discountAmount, BigDecimal totalAmount,
                                  String currency, String contactName, String contactEmail, String contactPhone,
                                  String specialRequests, Instant expiresAt, Instant confirmedAt, Instant cancelledAt,
                                  String cancelReason, Boolean refundEligible, Instant freeCancellationUntil,
                                  Instant createdAt, List<HistoryEntry> history) {

        public static BookingResponse from(Booking b) {
            return new BookingResponse(b.getId(), b.getBookingCode(), b.getStatus(), b.getHotelId(), b.getHotelName(),
                    b.getHotelTimeZone(), b.getCheckIn(), b.getCheckOut(), b.getCheckInTime(), b.getNights(),
                    b.getAdults(), b.getChildren(),
                    b.getRooms().stream().map(r -> new QuoteLine(r.getRoomTypeId(), r.getRoomTypeName(), r.getQuantity(),
                            vnd(r.getUnitPrice()), r.getNights(), vnd(r.getLineSubtotal()))).toList(),
                    vnd(b.getSubtotalAmount()), b.getServiceFeePercent(), vnd(b.getFeeAmount()), b.getTaxRatePercent(),
                    vnd(b.getTaxAmount()), vnd(b.getDiscountAmount()), vnd(b.getTotalAmount()), b.getCurrencyCode(),
                    b.getContactName(), b.getContactEmail(), b.getContactPhone(), b.getSpecialRequests(),
                    b.getExpiresAt(), b.getConfirmedAt(), b.getCancelledAt(), b.getCancelReason(),
                    b.getRefundEligible(), b.freeCancellationUntil(), b.getCreatedAt(),
                    b.getHistory().stream().map(h -> new HistoryEntry(h.getFromStatus(), h.getToStatus(), h.getReason(),
                            h.getActor(), h.getCreatedAt())).toList());
        }
    }

    public record BookingSummary(UUID id, String bookingCode, BookingStatus status, UUID hotelId, String hotelName,
                                 LocalDate checkInDate, LocalDate checkOutDate, int nights, BigDecimal totalAmount,
                                 String currency, Instant expiresAt, Instant createdAt) {

        public static BookingSummary from(Booking b) {
            return new BookingSummary(b.getId(), b.getBookingCode(), b.getStatus(), b.getHotelId(), b.getHotelName(),
                    b.getCheckIn(), b.getCheckOut(), b.getNights(), vnd(b.getTotalAmount()), b.getCurrencyCode(),
                    b.getExpiresAt(), b.getCreatedAt());
        }
    }

    public record StatusResponse(UUID id, BookingStatus status, Instant expiresAt) {
    }

    static BigDecimal vnd(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.UNNECESSARY);
    }
}
