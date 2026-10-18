package com.stayhotel.booking.booking;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Kafka payloads (API_CONTRACT.md M4 "Events"). Ids and amounts only: no names, emails or payment details. */
public final class BookingEvents {

    private BookingEvents() {
    }

    // published on booking-events
    public record BookingCreated(UUID bookingId, UUID hotelId, LocalDate checkInDate, LocalDate checkOutDate,
                                 BigDecimal totalAmount, String currency) {
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
