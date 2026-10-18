package com.stayhotel.payment.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    /** Only the booking id: the amount is always read from booking-service (BR-22). */
    public record CreatePaymentRequest(@NotNull UUID bookingId) {
    }

    public record RefundRequest(@NotBlank @Size(max = 300) String reason) {
    }

    public record RefundView(UUID id, BigDecimal amount, Refund.Status status, Instant createdAt, Instant completedAt) {
    }

    public record PaymentResponse(UUID id, UUID bookingId, BigDecimal amount, String currency, String provider,
                                  PaymentStatus status, String checkoutUrl, String failureReason, boolean needsReview,
                                  Instant createdAt, Instant succeededAt, RefundView refund) {

        static PaymentResponse from(Payment p, Refund refund) {
            boolean open = p.getStatus() == PaymentStatus.PENDING;
            return new PaymentResponse(p.getId(), p.getBookingId(), vnd(p.getAmount()), p.getCurrencyCode(),
                    p.getProvider(), p.getStatus(), open ? p.getCheckoutUrl() : null, p.getFailureReason(),
                    p.isNeedsReview(), p.getCreatedAt(), p.getSucceededAt(),
                    refund == null ? null : new RefundView(refund.getId(), vnd(refund.getAmount()), refund.getStatus(),
                            refund.getCreatedAt(), refund.getCompletedAt()));
        }
    }

    static BigDecimal vnd(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.UNNECESSARY);
    }
}
