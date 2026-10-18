package com.stayhotel.payment.payment;

import java.math.BigDecimal;
import java.util.UUID;

/** Event payloads exchanged over Kafka (API_CONTRACT.md M4 "Events"). No personal or card data. */
public final class PaymentEvents {

    private PaymentEvents() {
    }

    // published on payment-events
    public record PaymentSucceeded(UUID paymentId, UUID bookingId, BigDecimal amount, String currency) {
    }

    public record PaymentFailed(UUID paymentId, UUID bookingId, String reason) {
    }

    public record RefundRequested(UUID paymentId, UUID bookingId, UUID refundId) {
    }

    public record RefundSucceeded(UUID paymentId, UUID bookingId, UUID refundId, BigDecimal amount) {
    }

    public record RefundFailed(UUID paymentId, UUID bookingId, UUID refundId) {
    }

    // consumed from booking-events
    public record BookingCancelled(UUID bookingId, Boolean refundEligible) {
    }

    public record BookingPaymentRejected(UUID bookingId, UUID paymentId, String reason) {
    }

    /** Spring application event: a refund row was committed and must be submitted to the provider. */
    public record RefundCreated(UUID refundId) {
    }
}
