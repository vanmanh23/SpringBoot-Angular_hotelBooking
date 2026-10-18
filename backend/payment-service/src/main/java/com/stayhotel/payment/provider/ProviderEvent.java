package com.stayhotel.payment.provider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Verified provider notification. {@code merchantReference} is our payment id; {@code refundReference} is our
 * refund id for refund events.
 */
public record ProviderEvent(String eventId, String type, String sessionId, UUID merchantReference, UUID refundReference,
                            BigDecimal amount, String currency, Instant occurredAt, String reason) {

    public static final String PAYMENT_SUCCEEDED = "payment.succeeded";
    public static final String PAYMENT_FAILED = "payment.failed";
    public static final String REFUND_SUCCEEDED = "refund.succeeded";
    public static final String REFUND_FAILED = "refund.failed";
}
