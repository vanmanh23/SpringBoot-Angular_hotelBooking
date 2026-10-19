package com.stayhotel.review;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class ReviewDtos {

    private ReviewDtos() {
    }

    public record ReviewRequest(@NotNull UUID bookingId, @NotNull @Min(1) @Max(5) Integer rating,
                                @Size(max = 2000) String comment) {
    }

    public record ReviewEdit(@NotNull @Min(1) @Max(5) Integer rating, @Size(max = 2000) String comment) {
    }

    public record VisibilityRequest(@NotNull Review.Status status, @Size(max = 300) String reason) {
    }

    public record Eligibility(boolean eligible, UUID reviewId) {
    }

    /** Authors are shown as "Verified guest": reviews never expose personal data. */
    public record ReviewResponse(UUID id, UUID hotelId, UUID bookingId, int rating, String comment, Review.Status status,
                                 String author, boolean mine, Instant createdAt, Instant updatedAt) {
        static ReviewResponse from(Review r, UUID viewer) {
            return new ReviewResponse(r.getId(), r.getHotelId(), r.getBookingId(), r.getRating(), r.getComment(),
                    r.getStatus(), "Verified guest", r.getUserId().equals(viewer), r.getCreatedAt(), r.getUpdatedAt());
        }
    }

    /** Moderation view: includes hidden reviews and the reason, still without personal data. */
    public record AdminReview(UUID id, UUID hotelId, UUID bookingId, int rating, String comment, Review.Status status,
                              String hiddenReason, Instant createdAt, Instant updatedAt) {
        static AdminReview from(Review r) {
            return new AdminReview(r.getId(), r.getHotelId(), r.getBookingId(), r.getRating(), r.getComment(),
                    r.getStatus(), r.getHiddenReason(), r.getCreatedAt(), r.getUpdatedAt());
        }
    }

    // ---- events
    public record HotelRatingChanged(UUID hotelId, BigDecimal avgRating, int reviewCount, long version) {
    }

    public record BookingCompleted(UUID bookingId, UUID userId, UUID hotelId, LocalDate checkOutDate) {
    }
}
