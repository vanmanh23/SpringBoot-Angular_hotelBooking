package com.stayhotel.payment.provider;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Port to an external payment gateway. Outcomes are NOT returned here: they arrive later as signed webhooks
 * ({@link ProviderEvent}), which is the only source of truth for payment results (BR-23).
 */
public interface PaymentProvider {

    Checkout createCheckout(UUID merchantReference, BigDecimal amount, String currency, String returnUrl);

    /**
     * Requests a full refund. {@code idempotencyKey} must be stable for one refund so that retries are safe.
     * @throws ProviderUnavailableException when the provider cannot be reached (caller retries later)
     */
    void requestRefund(String idempotencyKey, String sessionId, BigDecimal amount);

    record Checkout(String sessionId, String url) {
    }

    class ProviderUnavailableException extends RuntimeException {
        public ProviderUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
