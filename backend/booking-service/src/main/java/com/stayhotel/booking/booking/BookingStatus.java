package com.stayhotel.booking.booking;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Booking lifecycle (design 3.7.1, BR-18). Independent of the payment state machine (BR-25): payment-service
 * only triggers PENDING_PAYMENT -> CONFIRMED after a verified payment (M4).
 */
public enum BookingStatus {
    PENDING_PAYMENT,
    CONFIRMED,
    CANCELLED,
    EXPIRED,
    COMPLETED;

    private static final Map<BookingStatus, Set<BookingStatus>> ALLOWED = Map.of(
            PENDING_PAYMENT, EnumSet.of(CONFIRMED, CANCELLED, EXPIRED),
            CONFIRMED, EnumSet.of(CANCELLED, COMPLETED),
            CANCELLED, EnumSet.noneOf(BookingStatus.class),
            // Only the late-payment policy uses this edge, after re-reserving every night (D-024).
            EXPIRED, EnumSet.of(CONFIRMED),
            COMPLETED, EnumSet.noneOf(BookingStatus.class));

    public boolean canTransitionTo(BookingStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    /** Statuses whose rooms are counted as taken (BR-10). */
    public boolean holdsInventory() {
        return this == PENDING_PAYMENT || this == CONFIRMED;
    }
}
