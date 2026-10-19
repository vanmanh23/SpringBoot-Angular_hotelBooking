package com.stayhotel.booking.booking;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Kafka payloads (API_CONTRACT.md M4/M5 "Events"). No payment details; personal data only in BookingCreated. */
public final class BookingEvents {

    private BookingEvents() {
    }

    // published on booking-events
    /**
     * Carries the contact data notification-service needs (D-029); it is the ONLY event with personal data, and no
     * later event repeats it. Consumers must not log it.
     */
    public record BookingCreated(UUID bookingId, UUID userId, UUID hotelId, String hotelName, String bookingCode,
                                 LocalDate checkInDate, LocalDate checkOutDate, BigDecimal totalAmount, String currency,
                                 String contactName, String contactEmail) {
    }

    /** Feeds review eligibility (D-027). */
    public record BookingCompleted(UUID bookingId, UUID userId, UUID hotelId, LocalDate checkOutDate) {
    }

    public record BookingConfirmed(UUID bookingId, UUID paymentId, boolean latePayment) {
    }

    public record BookingExpired(UUID bookingId) {
    }

    public record BookingCancelled(UUID bookingId, Boolean refundEligible) {
    }

    public record BookingPaymentRejected(UUID bookingId, UUID paymentId, String reason) {
    }

    // consumed from payment-events
    public record PaymentSucceeded(UUID paymentId, UUID bookingId, BigDecimal amount, String currency) {
    }

    public record RefundUpdate(UUID paymentId, UUID bookingId, UUID refundId) {
    }
}
