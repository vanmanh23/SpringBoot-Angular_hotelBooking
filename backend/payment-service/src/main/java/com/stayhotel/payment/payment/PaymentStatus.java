package com.stayhotel.payment.payment;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Payment lifecycle (design 3.7.2). Independent of the booking lifecycle (BR-25). */
public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    REFUND_PENDING,
    REFUNDED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = Map.of(
            PENDING, EnumSet.of(SUCCEEDED, FAILED, CANCELLED),
            SUCCEEDED, EnumSet.of(REFUND_PENDING),
            REFUND_PENDING, EnumSet.of(REFUNDED, SUCCEEDED),   // SUCCEEDED again = refund failed, retry later
            FAILED, EnumSet.noneOf(PaymentStatus.class),
            CANCELLED, EnumSet.noneOf(PaymentStatus.class),
            REFUNDED, EnumSet.noneOf(PaymentStatus.class));

    public boolean canTransitionTo(PaymentStatus target) {
        return ALLOWED.get(this).contains(target);
    }
}
