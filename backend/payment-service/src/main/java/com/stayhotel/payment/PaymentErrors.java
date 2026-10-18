package com.stayhotel.payment;

import com.stayhotel.common.error.BusinessException;
import org.springframework.http.HttpStatus;

/** Error codes of payment-service (documented in API_CONTRACT.md, M4). */
public final class PaymentErrors {

    private PaymentErrors() {
    }

    public static BusinessException paymentNotFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found");
    }

    public static BusinessException bookingNotFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "Booking not found");
    }

    public static BusinessException bookingNotPayable() {
        return new BusinessException(HttpStatus.CONFLICT, "BOOKING_NOT_PAYABLE",
                "This booking cannot be paid (it is not awaiting payment or its hold has ended)");
    }

    public static BusinessException alreadyPaid() {
        return new BusinessException(HttpStatus.CONFLICT, "PAYMENT_IN_PROGRESS", "This booking already has a successful payment");
    }

    public static BusinessException idempotencyKeyReused() {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                "This Idempotency-Key was already used for a different booking");
    }

    public static BusinessException notRefundable() {
        return new BusinessException(HttpStatus.CONFLICT, "PAYMENT_NOT_REFUNDABLE", "Only successful payments can be refunded");
    }

    public static BusinessException invalidSignature() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "Invalid webhook signature");
    }

    public static BusinessException providerUnavailable() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_PROVIDER_UNAVAILABLE",
                "The payment provider is temporarily unavailable. Please try again.");
    }

    public static BusinessException bookingServiceUnavailable() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "BOOKING_SERVICE_UNAVAILABLE",
                "Booking information is temporarily unavailable. Please try again.");
    }
}
