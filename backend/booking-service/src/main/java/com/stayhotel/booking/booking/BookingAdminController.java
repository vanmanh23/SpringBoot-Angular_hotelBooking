package com.stayhotel.booking.booking;

import com.stayhotel.booking.catalog.HotelManagementAccess;
import com.stayhotel.common.web.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/** Read-only booking views for hotel managers (own hotels, UC-11) and administrators (FR-ADMIN-03). */
@RestController
class BookingAdminController {

    private final BookingRepository bookings;
    private final HotelManagementAccess access;

    BookingAdminController(BookingRepository bookings, HotelManagementAccess access) {
        this.bookings = bookings;
        this.access = access;
    }

    @GetMapping("/api/v1/manage/hotels/{hotelId}/bookings")
    @Transactional(readOnly = true)
    public PageResponse<ManagedBooking> forHotel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID hotelId,
                                                 @RequestParam(required = false) BookingStatus status,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                 @RequestParam(defaultValue = "0") @Min(0) int page,
                                                 @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        access.requireManage(hotelId, jwt.getTokenValue());
        Specification<Booking> spec = (root, q, cb) -> cb.equal(root.get("hotelId"), hotelId);
        spec = spec.and(statusIs(status));
        if (from != null) {
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("checkOut"), from));
        }
        if (to != null) {
            spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("checkIn"), to));
        }
        return PageResponse.from(bookings.findAll(spec, PageRequest.of(page, size, Sort.by("checkIn", "id"))),
                ManagedBooking::from);
    }

    @GetMapping("/api/v1/admin/bookings")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public PageResponse<ManagedBooking> all(@RequestParam(required = false) BookingStatus status,
                                            @RequestParam(required = false) UUID hotelId,
                                            @RequestParam(required = false) @Size(max = 30) String code,
                                            @RequestParam(defaultValue = "0") @Min(0) int page,
                                            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Specification<Booking> spec = statusIs(status);
        if (hotelId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("hotelId"), hotelId));
        }
        if (code != null && !code.isBlank()) {
            String normalized = code.trim().toUpperCase(Locale.ROOT);
            spec = spec.and((root, q, cb) -> cb.equal(root.get("bookingCode"), normalized));
        }
        return PageResponse.from(bookings.findAll(spec, PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")))), ManagedBooking::from);
    }

    private static Specification<Booking> statusIs(BookingStatus status) {
        return (root, q, cb) -> status == null ? cb.conjunction() : cb.equal(root.get("status"), status);
    }

    /** Staff view: includes the contact name (needed at the front desk), not the payment details. */
    public record ManagedBooking(UUID id, String bookingCode, BookingStatus status, UUID hotelId, String hotelName,
                                 LocalDate checkInDate, LocalDate checkOutDate, int nights, int adults, int children,
                                 String contactName, BigDecimal totalAmount, String currency,
                                 Booking.RefundStatus refundStatus, Instant createdAt) {
        static ManagedBooking from(Booking b) {
            return new ManagedBooking(b.getId(), b.getBookingCode(), b.getStatus(), b.getHotelId(), b.getHotelName(),
                    b.getCheckIn(), b.getCheckOut(), b.getNights(), b.getAdults(), b.getChildren(), b.getContactName(),
                    BookingDtos.vnd(b.getTotalAmount()), b.getCurrencyCode(), b.getRefundStatus(), b.getCreatedAt());
        }
    }
}
